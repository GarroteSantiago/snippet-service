package org.snippet.snippet.processing

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Latest job published per snippet and type (`snippet_job`), to tell stale results apart. */
interface JobTracker {
    /** [jobId] becomes the latest job of its type for the snippet. */
    fun record(
        snippetId: UUID,
        type: JobType,
        jobId: UUID,
    )

    fun latest(
        snippetId: UUID,
        type: JobType,
    ): UUID?

    /** Forgets the latest job of [type], so any result still on its way is ignored. */
    fun forget(
        snippetId: UUID,
        type: JobType,
    )
}

/** Last `parserVersion` seen from the Runner (`parser_state`, US-18). */
interface ParserVersionStore {
    fun current(): String?

    /**
     * Stores [version] if it's different from the current one. Atomic, so when several replicas
     * notice the change at once only one gets true (and publishes the VALIDATE jobs).
     */
    fun change(version: String): Boolean
}

@Repository
class JdbcJobTracker(
    private val jdbc: JdbcTemplate,
) : JobTracker {
    override fun record(
        snippetId: UUID,
        type: JobType,
        jobId: UUID,
    ) {
        jdbc.update(
            """
            INSERT INTO snippet_job (snippet_id, job_type, job_id, created_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (snippet_id, job_type) DO UPDATE SET job_id = EXCLUDED.job_id, created_at = EXCLUDED.created_at
            """.trimIndent(),
            snippetId,
            type.name,
            jobId,
            Timestamp.from(Instant.now()),
        )
    }

    override fun latest(
        snippetId: UUID,
        type: JobType,
    ): UUID? =
        jdbc
            .query(
                "SELECT job_id FROM snippet_job WHERE snippet_id = ? AND job_type = ?",
                { rs, _ -> rs.getObject("job_id", UUID::class.java) },
                snippetId,
                type.name,
            ).firstOrNull()

    override fun forget(
        snippetId: UUID,
        type: JobType,
    ) {
        jdbc.update("DELETE FROM snippet_job WHERE snippet_id = ? AND job_type = ?", snippetId, type.name)
    }
}

@Repository
class JdbcParserVersionStore(
    private val jdbc: JdbcTemplate,
) : ParserVersionStore {
    override fun current(): String? =
        jdbc.query("SELECT parser_version FROM parser_state WHERE id = 1") { rs, _ -> rs.getString(1) }.firstOrNull()

    override fun change(version: String): Boolean =
        jdbc.update(
            "UPDATE parser_state SET parser_version = ?, updated_at = ? WHERE id = 1 AND parser_version IS DISTINCT FROM ?",
            version,
            Timestamp.from(Instant.now()),
            version,
        ) > 0
}
