package org.snippet.snippet.support

import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.permission.Action
import org.snippet.snippet.clients.permission.PermissionCheck
import org.snippet.snippet.clients.permission.PermissionClient
import org.snippet.snippet.clients.permission.RelationFilter
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.clients.permission.SnippetAccess
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.clients.runner.ValidationResult
import org.snippet.snippet.clients.runner.Versions
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.common.error.UpstreamException
import java.util.UUID

// In-memory stand-ins for the services we call, with switches to make them fail.

/** Code containing [INVALID] doesn't parse. */
class FakeRunner : RunnerClient {
    var versions = Versions("printscript", listOf("1.0", "1.1"), "1.1")
    val formatRules = listOf(RuleConfig("indentation", true, 4, "Spaces per level"))
    var lastFormatRules: List<RuleConfig>? = null

    override fun versions() = versions

    override fun validate(
        content: String,
        version: String,
    ) = if (INVALID in content) ValidationResult(false, listOf(DIAGNOSTIC)) else ValidationResult(true)

    override fun defaultRules(ruleType: RuleType) = formatRules

    override fun format(
        content: String,
        version: String,
        rules: List<RuleConfig>,
    ): String {
        if (INVALID in content) throw InvalidCodeException(listOf(DIAGNOSTIC))
        lastFormatRules = rules
        return content.trim() + "\n"
    }

    companion object {
        const val INVALID = "!!"
        val DIAGNOSTIC = Diagnostic(null, "Unexpected '!'", 1, 3)
    }
}

class FakePermissions : PermissionClient {
    val roles = mutableMapOf<Pair<UUID, String>, Role>()
    var failGrant = false

    override fun grant(
        snippetId: UUID,
        userId: String,
        role: Role,
    ) {
        if (failGrant) throw UpstreamException("Permission Service", "down")
        roles[snippetId to userId] = role
    }

    override fun check(
        snippetId: UUID,
        userId: String,
        action: Action,
    ): PermissionCheck {
        val role = roles[snippetId to userId]
        val allowed = role == Role.OWNER || (role == Role.READ && action == Action.READ)
        return PermissionCheck(allowed, role)
    }

    override fun accessibleSnippets(
        userId: String,
        relation: RelationFilter,
    ) = roles
        .filter { (key, role) ->
            key.second == userId &&
                when (relation) {
                    RelationFilter.OWNED -> role == Role.OWNER
                    RelationFilter.SHARED -> role == Role.READ
                    RelationFilter.ALL -> true
                }
        }.map { (key, role) -> SnippetAccess(key.first, role) }

    override fun revokeAll(snippetId: UUID) {
        roles.keys.removeIf { it.first == snippetId }
    }
}

class FakeAssets : AssetClient {
    val stored = mutableMapOf<Pair<String, String>, String>()
    var failPut = false

    override fun put(
        container: String,
        key: String,
        content: String,
    ) {
        if (failPut) throw UpstreamException("Asset Service", "down")
        stored[container to key] = content
    }

    override fun get(
        container: String,
        key: String,
    ) = stored[container to key]

    override fun delete(
        container: String,
        key: String,
    ) {
        stored.remove(container to key)
    }
}
