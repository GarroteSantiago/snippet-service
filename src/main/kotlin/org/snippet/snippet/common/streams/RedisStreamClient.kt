package org.snippet.snippet.common.streams

import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.api.async.RedisStreamAsyncCommands
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import io.lettuce.core.Consumer as LettuceConsumer

/** [StreamClient] over Spring Data Redis (Lettuce). */
class RedisStreamClient(
    private val redis: StringRedisTemplate,
) : StreamClient {
    private val streams = redis.opsForStream<String, String>()

    override fun createGroup(
        stream: String,
        group: String,
    ) {
        try {
            redis.execute(
                RedisCallback { connection ->
                    connection.streamCommands().xGroupCreate(stream.toByteArray(), group, ReadOffset.latest(), true)
                },
            )
        } catch (e: Exception) {
            // Another replica (or a previous run) already created it
            if (!e.isBusyGroup()) throw e
        }
    }

    override fun add(
        stream: String,
        fields: Map<String, String>,
        maxLength: Long,
    ): String {
        val record =
            StreamRecords
                .rawBytes(fields.entries.associate { (k, v) -> k.toByteArray() to v.toByteArray() })
                .withStreamKey(stream.toByteArray())
        val options = XAddOptions.maxlen(maxLength).approximateTrimming(true)
        return redis
            .execute(RedisCallback { connection -> connection.streamCommands().xAdd(record, options) })
            ?.value ?: error("XADD to $stream returned no id")
    }

    override fun readNew(
        stream: String,
        group: String,
        consumer: String,
        count: Long,
        block: Duration,
    ): List<StreamEntry> =
        streams
            .read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count).block(block),
                StreamOffset.create(stream, ReadOffset.lastConsumed()),
            ).orEmpty()
            .map { record -> StreamEntry(record.id.value, record.value.entries.associate { (k, v) -> k.toString() to v.toString() }) }

    // Spring Data Redis has no XAUTOCLAIM, so it goes through Lettuce directly
    override fun autoClaim(
        stream: String,
        group: String,
        consumer: String,
        minIdle: Duration,
        cursor: String,
        count: Long,
    ): ClaimedEntries =
        redis.execute(
            RedisCallback { connection ->
                val args =
                    XAutoClaimArgs.Builder
                        .xautoclaim(LettuceConsumer.from(group.toByteArray(), consumer.toByteArray()), minIdle, cursor)
                        .count(count)
                val claimed = connection.lettuceStreams().xautoclaim(stream.toByteArray(), args).get()
                ClaimedEntries(
                    next = claimed.id,
                    entries =
                        claimed.messages.map { message ->
                            StreamEntry(message.id, message.body.entries.associate { (k, v) -> String(k) to String(v) })
                        },
                )
            },
        ) ?: ClaimedEntries(CURSOR_START, emptyList())

    override fun deliveries(
        stream: String,
        group: String,
        id: String,
    ): Long =
        streams
            .pending(stream, group, Range.closed(id, id), 1)
            .firstOrNull()
            ?.totalDeliveryCount ?: 0

    override fun ack(
        stream: String,
        group: String,
        id: String,
    ) {
        streams.acknowledge(stream, group, id)
    }

    override fun stats(
        stream: String,
        group: String,
    ): StreamStats {
        // XINFO GROUPS fails on a stream that doesn't exist yet
        if (redis.hasKey(stream) != true) return StreamStats(0, null, null)
        val length = streams.size(stream) ?: 0
        val info = streams.groups(stream).firstOrNull { it.groupName() == group } ?: return StreamStats(length, null, null)
        // `lag` (Redis 7) has no accessor; Redis answers nil when it can't tell (after some deletions)
        val lag = info.raw["lag"]?.toString()?.toLongOrNull()
        return StreamStats(length, lag, info.pendingCount())
    }

    @Suppress("UNCHECKED_CAST")
    private fun RedisConnection.lettuceStreams() = nativeConnection as RedisStreamAsyncCommands<ByteArray, ByteArray>

    private fun Throwable.isBusyGroup(): Boolean = generateSequence(this) { it.cause }.any { it.message?.contains("BUSYGROUP") == true }

    companion object {
        const val CURSOR_START = "0-0"
    }
}
