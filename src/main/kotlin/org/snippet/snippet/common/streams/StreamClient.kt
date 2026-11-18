package org.snippet.snippet.common.streams

import java.time.Duration

/** An entry of a Redis stream: its id and its fields (`type`, `correlationId`, `payload`). */
data class StreamEntry(
    val id: String,
    val fields: Map<String, String>,
)

/**
 * The Redis Streams commands the consumers and producers need (`contracts/redis-streams.md`).
 * An interface so the processing logic can be tested with an in-memory fake.
 */
interface StreamClient {
    /** `XGROUP CREATE <stream> <group> $ MKSTREAM`; a group that already exists (`BUSYGROUP`) is fine. */
    fun createGroup(
        stream: String,
        group: String,
    )

    /** `XADD <stream> MAXLEN ~ <maxLength> * ...`. Returns the new entry id. */
    fun add(
        stream: String,
        fields: Map<String, String>,
        maxLength: Long,
    ): String

    /** `XREADGROUP GROUP <group> <consumer> COUNT <count> BLOCK <block> STREAMS <stream> >`: entries never delivered to the group. */
    fun readNew(
        stream: String,
        group: String,
        consumer: String,
        count: Long,
        block: Duration,
    ): List<StreamEntry>

    /**
     * `XAUTOCLAIM <stream> <group> <consumer> <minIdle> <cursor> COUNT <count>`: takes over entries
     * delivered but not acked for more than [minIdle]. Each claim counts as one more delivery.
     */
    fun autoClaim(
        stream: String,
        group: String,
        consumer: String,
        minIdle: Duration,
        cursor: String,
        count: Long,
    ): ClaimedEntries

    /** Times the entry was delivered, from `XPENDING`; 0 if it isn't pending. */
    fun deliveries(
        stream: String,
        group: String,
        id: String,
    ): Long

    /** `XACK`. */
    fun ack(
        stream: String,
        group: String,
        id: String,
    )
}

/** A page of `XAUTOCLAIM`: [next] is `0-0` when there is nothing left to scan. */
data class ClaimedEntries(
    val next: String,
    val entries: List<StreamEntry>,
)
