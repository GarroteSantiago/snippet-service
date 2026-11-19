package org.snippet.snippet.common.streams

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A consumer group whose backlog is measured. */
data class WatchedStream(
    val stream: String,
    val group: String,
)

/**
 * Saturation of the Redis pipeline as gauges tagged with `stream` and `group`:
 *
 * - `redis.stream.length`: entries in the stream (`XLEN`; capped by `MAXLEN ~`).
 * - `redis.stream.lag`: entries the group hasn't read yet. Redis only knows it once the group
 *   has caught up with the stream at least once since it was created.
 * - `redis.stream.pending`: entries read but not acked (`XPENDING`), i.e. in progress or stuck.
 *
 * [refresh] asks Redis and the gauges report its last answer, so a scrape never waits on Redis.
 * A value is NaN until the first answer, while Redis is down, or while the group doesn't exist.
 */
class StreamMetrics(
    private val client: StreamClient,
    private val watched: List<WatchedStream>,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Values {
        val length = AtomicReference(Double.NaN)
        val lag = AtomicReference(Double.NaN)
        val pending = AtomicReference(Double.NaN)
    }

    private val values = watched.associateWith { Values() }

    init {
        values.forEach { (watch, value) ->
            gauge(registry, "redis.stream.length", "Entries in the stream", watch, value.length)
            gauge(registry, "redis.stream.lag", "Entries the consumer group hasn't read yet", watch, value.lag)
            gauge(registry, "redis.stream.pending", "Entries read by the consumer group but not acked", watch, value.pending)
        }
    }

    fun refresh() {
        values.forEach { (watch, value) ->
            val stats =
                try {
                    client.stats(watch.stream, watch.group)
                } catch (e: Exception) {
                    log.warn("Could not read the stats of {} for group {}: {}", watch.stream, watch.group, e.message)
                    null
                }
            value.length.set(stats?.length?.toDouble() ?: Double.NaN)
            value.lag.set(stats?.lag?.toDouble() ?: Double.NaN)
            value.pending.set(stats?.pending?.toDouble() ?: Double.NaN)
        }
    }

    private fun gauge(
        registry: MeterRegistry,
        name: String,
        description: String,
        watch: WatchedStream,
        value: AtomicReference<Double>,
    ) {
        Gauge
            .builder(name, value) { it.get() }
            .description(description)
            .tag("stream", watch.stream)
            .tag("group", watch.group)
            .register(registry)
    }
}

/** Runs [StreamMetrics.refresh] every [interval] while the application is up. */
class StreamMetricsLifecycle(
    private val metrics: StreamMetrics,
    private val interval: Duration,
) : SmartLifecycle {
    @Volatile
    private var running = false
    private var scheduler: ScheduledExecutorService? = null

    override fun start() {
        running = true
        scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("stream-metrics").factory()).apply {
                scheduleWithFixedDelay({ if (running) metrics.refresh() }, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
            }
    }

    override fun stop() {
        running = false
        // A refresh in progress finishes before Redis' connection factory stops
        scheduler?.shutdown()
        scheduler?.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun isRunning() = running

    companion object {
        private const val STOP_TIMEOUT_SECONDS = 5L
    }
}
