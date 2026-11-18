package org.snippet.snippet.support

import org.snippet.snippet.common.streams.ClaimedEntries
import org.snippet.snippet.common.streams.StreamClient
import org.snippet.snippet.common.streams.StreamEntry
import java.time.Duration

/**
 * In-memory Redis Streams with the semantics the workers rely on: consumer groups, a pending list
 * with delivery counts, `XAUTOCLAIM` by idle time and `XACK`. Time only moves with [advance].
 */
class FakeStreamClient : StreamClient {
    private class Pending(
        var consumer: String,
        var deliveries: Long,
        var deliveredAt: Long,
    )

    private class Group {
        var lastDelivered = -1
        val pending = linkedMapOf<String, Pending>()
    }

    val streams = mutableMapOf<String, MutableList<StreamEntry>>()
    private val groups = mutableMapOf<Pair<String, String>, Group>()
    private var now = 0L
    private var sequence = 0
    var failAdd = false

    fun advance(duration: Duration) {
        now += duration.toMillis()
    }

    fun entries(stream: String): List<StreamEntry> = streams[stream].orEmpty()

    fun pending(
        stream: String,
        group: String,
    ): Set<String> = groups[stream to group]?.pending?.keys.orEmpty()

    override fun createGroup(
        stream: String,
        group: String,
    ) {
        val entries = streams.getOrPut(stream) { mutableListOf() }
        // `$`: the group starts after what's already there
        groups.getOrPut(stream to group) { Group().apply { lastDelivered = entries.lastIndex } }
    }

    override fun add(
        stream: String,
        fields: Map<String, String>,
        maxLength: Long,
    ): String {
        if (failAdd) throw IllegalStateException("Redis down")
        val id = "${++sequence}-0"
        streams.getOrPut(stream) { mutableListOf() } += StreamEntry(id, fields)
        return id
    }

    override fun readNew(
        stream: String,
        group: String,
        consumer: String,
        count: Long,
        block: Duration,
    ): List<StreamEntry> {
        val state = groups.getValue(stream to group)
        val entries = streams.getValue(stream)
        val delivered = entries.drop(state.lastDelivered + 1).take(count.toInt())
        state.lastDelivered += delivered.size
        delivered.forEach { state.pending[it.id] = Pending(consumer, 1, now) }
        return delivered
    }

    override fun autoClaim(
        stream: String,
        group: String,
        consumer: String,
        minIdle: Duration,
        cursor: String,
        count: Long,
    ): ClaimedEntries {
        val state = groups.getValue(stream to group)
        val claimed =
            state.pending.entries
                .filter { now - it.value.deliveredAt >= minIdle.toMillis() }
                .take(count.toInt())
        claimed.forEach { (_, pending) ->
            pending.consumer = consumer
            pending.deliveries++
            pending.deliveredAt = now
        }
        val byId = streams.getValue(stream).associateBy { it.id }
        return ClaimedEntries("0-0", claimed.mapNotNull { byId[it.key] })
    }

    override fun deliveries(
        stream: String,
        group: String,
        id: String,
    ): Long = groups[stream to group]?.pending?.get(id)?.deliveries ?: 0

    override fun ack(
        stream: String,
        group: String,
        id: String,
    ) {
        groups[stream to group]?.pending?.remove(id)
    }
}
