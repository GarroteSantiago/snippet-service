package org.snippet.snippet.common.streams

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.snippet.snippet.support.FakeStreamClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamWorkerTest {
    private val client = FakeStreamClient()
    private val properties = StreamProperties(minIdle = Duration.ofSeconds(60), maxDeliveries = 3)

    private class RecordingHandler : StreamHandler {
        val handled = mutableListOf<String>()
        val discarded = mutableListOf<Pair<String, Long>>()
        var fail = false

        override fun handle(entry: StreamEntry) {
            handled += entry.id
            if (fail) throw IllegalStateException("boom")
        }

        override fun discard(
            entry: StreamEntry,
            deliveries: Long,
        ) {
            discarded += entry.id to deliveries
        }
    }

    private val handler = RecordingHandler()
    private val meters = SimpleMeterRegistry()
    private val worker = StreamWorker(client, "jobs", "group", "me", handler, properties, meters).apply { createGroup() }

    private fun publish() = client.add("jobs", mapOf("type" to "LINT"), 10_000)

    @Test
    fun `acks an entry only after handling it`() {
        val id = publish()

        assertEquals(1, worker.pollOnce())

        assertEquals(listOf(id), handler.handled)
        assertTrue(client.pending("jobs", "group").isEmpty())
    }

    @Test
    fun `a failing entry stays pending and is retried once it's idle`() {
        val id = publish()
        handler.fail = true
        worker.pollOnce()
        assertEquals(setOf(id), client.pending("jobs", "group"))

        // Not idle long enough yet
        client.advance(Duration.ofSeconds(30))
        assertEquals(0, worker.reclaimOnce())

        handler.fail = false
        client.advance(Duration.ofSeconds(30))
        assertEquals(1, worker.reclaimOnce())
        assertEquals(listOf(id, id), handler.handled)
        assertTrue(client.pending("jobs", "group").isEmpty())
    }

    @Test
    fun `reclaims entries another consumer left pending`() {
        val id = publish()
        val crashed = StreamWorker(client, "jobs", "group", "crashed", RecordingHandler().apply { fail = true }, properties)
        crashed.pollOnce()

        client.advance(Duration.ofSeconds(61))
        worker.reclaimOnce()

        assertEquals(listOf(id), handler.handled)
        assertTrue(client.pending("jobs", "group").isEmpty())
    }

    @Test
    fun `discards and acks an entry delivered more than three times`() {
        val id = publish()
        handler.fail = true
        worker.pollOnce() // delivery 1
        repeat(2) {
            // deliveries 2 and 3 are still processed
            client.advance(Duration.ofSeconds(61))
            worker.reclaimOnce()
        }
        assertEquals(3, handler.handled.size)
        assertTrue(handler.discarded.isEmpty())

        client.advance(Duration.ofSeconds(61))
        worker.reclaimOnce() // delivery 4

        assertEquals(3, handler.handled.size)
        assertEquals(listOf(id to 4L), handler.discarded)
        assertTrue(client.pending("jobs", "group").isEmpty())
    }

    @Test
    fun `only reads entries published after the group was created`() {
        val client = FakeStreamClient()
        client.add("jobs", mapOf("type" to "old"), 10_000)
        val worker = StreamWorker(client, "jobs", "group", "me", handler, properties).apply { createGroup() }

        assertEquals(0, worker.pollOnce())
    }

    @Test
    fun `times each entry by outcome`() {
        publish()
        worker.pollOnce()
        handler.fail = true
        publish()
        worker.pollOnce()

        fun count(outcome: String) =
            meters
                .find(StreamWorker.METRIC)
                .tags("stream", "jobs", "group", "group", "outcome", outcome)
                .timer()
                ?.count()
        assertEquals(1, count(StreamWorker.OUTCOME_OK))
        assertEquals(1, count(StreamWorker.OUTCOME_ERROR))
    }
}
