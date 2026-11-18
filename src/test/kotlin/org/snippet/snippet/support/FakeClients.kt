package org.snippet.snippet.support

import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.auth0.UserDirectory
import org.snippet.snippet.clients.auth0.UserSearchResult
import org.snippet.snippet.clients.auth0.UserSummary
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
import org.snippet.snippet.clients.runner.TestResult
import org.snippet.snippet.clients.runner.TestStatus
import org.snippet.snippet.clients.runner.ValidationResult
import org.snippet.snippet.clients.runner.Versions
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.common.error.UpstreamException
import java.util.UUID

// In-memory stand-ins for the services we call, with switches to make them fail.

/** Code containing [INVALID] doesn't parse. */
class FakeRunner : RunnerClient {
    var versions = Versions("printscript", listOf("1.0", "1.1"), "1.1", "2.0.0")
    val formatRules = listOf(RuleConfig("indentation", true, 4, "Spaces per level"))
    val lintRules = listOf(RuleConfig("identifier_format", true, "camel case", "Naming style"))
    var versionCalls = 0
    var lastFormatRules: List<RuleConfig>? = null
    var lastTest: TestCall? = null

    override fun versions(): Versions {
        versionCalls++
        return versions
    }

    override fun validate(
        content: String,
        version: String,
    ) = if (INVALID in content) ValidationResult(false, listOf(DIAGNOSTIC)) else ValidationResult(true)

    override fun defaultRules(ruleType: RuleType) = if (ruleType == RuleType.FORMAT) formatRules else lintRules

    /** A value of [BAD_VALUE] is out of range, as the Runner would say. */
    override fun validateRules(
        ruleType: RuleType,
        rules: List<RuleConfig>,
        version: String,
    ) {
        rules.find { it.value == BAD_VALUE }?.let { throw BadRequestException("Rule '${it.name}' is out of range") }
    }

    override fun format(
        content: String,
        version: String,
        rules: List<RuleConfig>,
    ): String {
        if (INVALID in content) throw InvalidCodeException(listOf(DIAGNOSTIC))
        lastFormatRules = rules
        return content.trim() + "\n"
    }

    // The "program" prints its inputs back; code containing [INVALID] fails to run
    override fun test(
        content: String,
        version: String,
        inputs: List<String>,
        expectedOutputs: List<String>,
    ): TestResult {
        lastTest = TestCall(content, version, inputs, expectedOutputs)
        if (INVALID in content) return TestResult(TestStatus.FAIL, emptyList(), listOf(DIAGNOSTIC))
        return TestResult(if (inputs == expectedOutputs) TestStatus.SUCCESS else TestStatus.FAIL, inputs)
    }

    companion object {
        const val INVALID = "!!"
        const val BAD_VALUE = "out of range"
        val DIAGNOSTIC = Diagnostic(null, "Unexpected '!'", 1, 3)
    }
}

data class TestCall(
    val content: String,
    val version: String,
    val inputs: List<String>,
    val expectedOutputs: List<String>,
)

class FakePermissions : PermissionClient {
    val roles = mutableMapOf<Pair<UUID, String>, Role>()
    var failGrant = false

    // The token the last check was given explicitly
    var lastCheckToken: String? = null

    override fun grant(
        snippetId: UUID,
        userId: String,
        role: Role,
    ): Boolean {
        if (failGrant) throw UpstreamException("Permission Service", "down")
        // Like the real one: 409 if the user already has a permission, which stays as it was
        if ((snippetId to userId) in roles) return false
        roles[snippetId to userId] = role
        return true
    }

    override fun check(
        snippetId: UUID,
        userId: String,
        action: Action,
        token: String?,
    ): PermissionCheck {
        lastCheckToken = token
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

    // The token the last get was given explicitly
    var lastGetToken: String? = null

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
        token: String?,
    ): String? {
        lastGetToken = token
        return stored[container to key]
    }

    override fun delete(
        container: String,
        key: String,
    ) {
        stored.remove(container to key)
    }
}

/** The Auth0 users, by id. Search is a case-insensitive prefix match on the name. */
class FakeUsers(
    vararg users: UserSummary,
) : UserDirectory {
    val users = users.toMutableList()
    var fail = false

    override fun search(
        name: String?,
        excludedUserId: String,
        page: Int,
        pageSize: Int,
    ): UserSearchResult {
        if (fail) throw UpstreamException("Auth0 Management API", "down")
        val matching =
            users.filter { it.id != excludedUserId && it.name.startsWith(name.orEmpty(), ignoreCase = true) }
        return UserSearchResult(matching.size.toLong(), matching.drop(page * pageSize).take(pageSize))
    }

    override fun exists(userId: String): Boolean {
        if (fail) throw UpstreamException("Auth0 Management API", "down")
        return users.any { it.id == userId }
    }
}
