package org.snippet.snippet.common.streams

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What a consumer does with each entry of its stream. */
interface StreamHandler {
    /** Processes the entry. Returning acks it; throwing leaves it pending, to be reclaimed and retried. */
    fun handle(entry: StreamEntry)

    /** The entry was delivered more than `max-deliveries` times: give up on it. Returning acks it. */
    fun discard(
        entry: StreamEntry,
        deliveries: Long,
    )
}

/** `streams.*` in `application.yaml`. Defaults follow `contracts/redis-streams.md`. */
@ConfigurationProperties("streams")
data class StreamProperties(
    /** Off in tests that don't have Redis. */
    val enabled: Boolean = true,
    /** Blank means the hostname, so each replica (container) is its own consumer. */
    val consumerName: String = "",
    val maxLength: Long = 10_000,
    val batchSize: Long = 10,
    val block: Duration = Duration.ofSeconds(2),
    val reclaimInterval: Duration = Duration.ofSeconds(30),
    val minIdle: Duration = Duration.ofSeconds(60),
    val maxDeliveries: Long = 3,
) {
    fun consumer(): String = consumerName.ifBlank { InetAddress.getLocalHost().hostName }
}

/**
 * Consumes [stream] as [consumer] of [group] with manual ack (`contracts/redis-streams.md`):
 *
 * - [pollOnce] reads new entries; each one is acked only after [handler] returns.
 * - [reclaimOnce] takes over entries pending for more than `min-idle` (`XAUTOCLAIM`), from any
 *   consumer of the group, including a crashed one. Before processing a reclaimed entry it checks
 *   `XPENDING`: after more than `max-deliveries` it calls [StreamHandler.discard] and acks it.
 *   (An entry read with `>` is on its first delivery, so only reclaimed ones need the check.)
 *
 * A failing entry is not acked, so it waits `min-idle`, gets reclaimed and is retried.
 */
class StreamWorker(
    private val client: StreamClient,
    val stream: String,
    val group: String,
    val consumer: String,
    private val handler: StreamHandler,
    private val properties: StreamProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createGroup() = client.createGroup(stream, group)

    /** Returns how many entries were read. */
    fun pollOnce(): Int {
        val entries = client.readNew(stream, group, consumer, properties.batchSize, properties.block)
        entries.forEach { process(it, reclaimed = false) }
        return entries.size
    }

    /** Scans the whole pending list once. Returns how many entries were claimed. */
    fun reclaimOnce(): Int {
        var cursor = START
        var claimed = 0
        do {
            val page = client.autoClaim(stream, group, consumer, properties.minIdle, cursor, properties.batchSize)
            page.entries.forEach { process(it, reclaimed = true) }
            claimed += page.entries.size
            cursor = page.next
        } while (cursor != START)
        return claimed
    }

    private fun process(
        entry: StreamEntry,
        reclaimed: Boolean,
    ) {
        try {
            val deliveries = if (reclaimed) client.deliveries(stream, group, entry.id) else 1
            if (deliveries > properties.maxDeliveries) {
                log.warn("Discarding {} entry {} after {} deliveries", stream, entry.id, deliveries)
                handler.discard(entry, deliveries)
            } else {
                handler.handle(entry)
            }
            client.ack(stream, group, entry.id)
        } catch (e: Exception) {
            log.error("Processing {} entry {} failed; it stays pending to be retried", stream, entry.id, e)
        }
    }

    companion object {
        const val START = "0-0"
    }
}

/**
 * Runs a [StreamWorker] while the application is up: one thread polls for new entries and a
 * scheduler reclaims pending ones every `reclaim-interval`. Redis being down is logged and retried.
 */
class StreamWorkerLifecycle(
    private val worker: StreamWorker,
    private val properties: StreamProperties,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false
    private var poller: Thread? = null
    private var reclaimer: ScheduledExecutorService? = null

    override fun start() {
        running = true
        poller = Thread.ofVirtual().name("stream-${worker.stream}").start(::pollLoop)
        reclaimer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("reclaim-${worker.stream}").factory()).apply {
                val interval = properties.reclaimInterval.toMillis()
                scheduleWithFixedDelay(::reclaim, interval, interval, TimeUnit.MILLISECONDS)
            }
        log.info("Consuming {} as {} of group {}", worker.stream, worker.consumer, worker.group)
    }

    override fun stop() {
        running = false
        reclaimer?.shutdownNow()
        poller?.interrupt()
        poller?.join(properties.block.multipliedBy(2).toMillis())
    }

    override fun isRunning() = running

    private fun pollLoop() {
        var groupReady = false
        while (running) {
            try {
                if (!groupReady) {
                    worker.createGroup()
                    groupReady = true
                }
                worker.pollOnce()
            } catch (e: Exception) {
                if (!running) return
                log.error("Reading {} failed; retrying", worker.stream, e)
                try {
                    Thread.sleep(RETRY_DELAY.toMillis())
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun reclaim() {
        try {
            val claimed = worker.reclaimOnce()
            if (claimed > 0) log.info("Reclaimed {} pending {} entries", claimed, worker.stream)
        } catch (e: Exception) {
            log.error("Reclaiming pending {} entries failed", worker.stream, e)
        }
    }

    companion object {
        private val RETRY_DELAY = Duration.ofSeconds(2)
    }
}
