package org.snippet.snippet.common.streams

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.snippet.snippet.support.FakeStreamClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamMetricsTest {
    /** The fake Redis, whose `stats` can be made to fail. */
    private class FlakyStats(
        val redis: FakeStreamClient = FakeStreamClient(),
    ) : StreamClient by redis {
        var down = false

        override fun stats(
            stream: String,
            group: String,
        ): StreamStats = if (down) throw IllegalStateException("Redis down") else redis.stats(stream, group)
    }

    private val client = FlakyStats()
    private val meters = SimpleMeterRegistry()
    private val metrics = StreamMetrics(client, listOf(WatchedStream("jobs", "group")), meters)

    private fun gauge(name: String) =
        meters
            .get(name)
            .tags("stream", "jobs", "group", "group")
            .gauge()
            .value()

    private fun publish() = client.add("jobs", mapOf("type" to "LINT"), 10_000)

    @Test
    fun `gauges are registered and unknown until Redis answers`() {
        listOf("redis.stream.length", "redis.stream.lag", "redis.stream.pending").forEach { assertTrue(gauge(it).isNaN(), it) }
    }

    @Test
    fun `report length, lag and pending entries of the group`() {
        val failing =
            object : StreamHandler {
                override fun handle(entry: StreamEntry) = throw IllegalStateException("not now")

                override fun discard(
                    entry: StreamEntry,
                    deliveries: Long,
                ) = Unit
            }
        val worker = StreamWorker(client, "jobs", "group", "me", failing, StreamProperties(batchSize = 1)).apply { createGroup() }
        repeat(3) { publish() }
        // Reads one entry and leaves it pending
        worker.pollOnce()

        metrics.refresh()

        assertEquals(3.0, gauge("redis.stream.length"))
        assertEquals(2.0, gauge("redis.stream.lag"))
        assertEquals(1.0, gauge("redis.stream.pending"))
    }

    @Test
    fun `a group that doesn't exist yet has unknown lag and pending`() {
        publish()

        metrics.refresh()

        assertEquals(1.0, gauge("redis.stream.length"))
        assertTrue(gauge("redis.stream.lag").isNaN())
        assertTrue(gauge("redis.stream.pending").isNaN())
    }

    @Test
    fun `while Redis is down the values are unknown`() {
        publish()
        metrics.refresh()
        assertEquals(1.0, gauge("redis.stream.length"))

        client.down = true
        metrics.refresh()

        assertTrue(gauge("redis.stream.length").isNaN())
    }
}
