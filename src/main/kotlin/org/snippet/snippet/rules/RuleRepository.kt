package org.snippet.snippet.rules

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** Each user's rules per type (`user_rules`). */
interface RuleRepository {
    /** null when the user never saved rules of this type. */
    fun find(
        ownerId: String,
        type: RuleType,
    ): List<RuleConfig>?

    /** Replaces the user's rules of this type. */
    fun save(
        ownerId: String,
        type: RuleType,
        rules: List<RuleConfig>,
    )
}

@Repository
class JdbcRuleRepository(
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) : RuleRepository {
    override fun find(
        ownerId: String,
        type: RuleType,
    ): List<RuleConfig>? =
        jdbc
            .query(
                "SELECT rules FROM user_rules WHERE owner_id = ? AND rule_type = ?",
                { rs, _ -> json.readValue<List<RuleConfig>>(rs.getString("rules")) },
                ownerId,
                type.path,
            ).firstOrNull()

    override fun save(
        ownerId: String,
        type: RuleType,
        rules: List<RuleConfig>,
    ) {
        jdbc.update(
            """
            INSERT INTO user_rules (owner_id, rule_type, rules, updated_at) VALUES (?, ?, ?::jsonb, ?)
            ON CONFLICT (owner_id, rule_type) DO UPDATE SET rules = EXCLUDED.rules, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            ownerId,
            type.path,
            json.writeValueAsString(rules.map { it.copy(description = null) }),
            Timestamp.from(Instant.now()),
        )
    }
}
