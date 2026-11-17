package org.snippet.snippet.clients.permission

import org.snippet.snippet.clients.upstream
import org.snippet.snippet.common.error.UpstreamException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.util.UUID

enum class Role { OWNER, READ }

/** `WRITE` is only for the `OWNER`; `READ` for both roles. */
enum class Action { READ, WRITE }

/** Same values as the `relation` filter of the public API. */
enum class RelationFilter(
    val value: String,
) {
    OWNED("owned"),
    SHARED("shared"),
    ALL("all"),
}

data class PermissionCheck(
    val allowed: Boolean,
    // null when the user has no permission at all on the snippet (or it doesn't exist)
    val role: Role? = null,
)

data class SnippetAccess(
    val snippetId: UUID,
    val role: Role,
)

/** Permission Service (`contracts/permission-service.openapi.yaml`). */
interface PermissionClient {
    /** false if the user already had a permission on the snippet (the service answers 409); it is left as it was. */
    fun grant(
        snippetId: UUID,
        userId: String,
        role: Role,
    ): Boolean

    fun check(
        snippetId: UUID,
        userId: String,
        action: Action,
    ): PermissionCheck

    fun accessibleSnippets(
        userId: String,
        relation: RelationFilter,
    ): List<SnippetAccess>

    /** Idempotent. */
    fun revokeAll(snippetId: UUID)
}

@Component
class RestPermissionClient(
    builder: RestClient.Builder,
    @Value("\${services.permission.url}") baseUrl: String,
) : PermissionClient {
    private val client = builder.baseUrl(baseUrl).build()

    override fun grant(
        snippetId: UUID,
        userId: String,
        role: Role,
    ): Boolean =
        try {
            upstream(SERVICE) {
                client
                    .post()
                    .uri("/permissions")
                    .body(Permission(snippetId, userId, role))
                    .retrieve()
                    .toBodilessEntity()
            }
            true
        } catch (e: UpstreamException) {
            if (e.cause is HttpClientErrorException.Conflict) false else throw e
        }

    override fun check(
        snippetId: UUID,
        userId: String,
        action: Action,
    ): PermissionCheck =
        upstream(SERVICE) {
            client
                .get()
                .uri("/permissions/check?snippetId={snippetId}&userId={userId}&action={action}", snippetId, userId, action)
                .retrieve()
                .body<PermissionCheck>()
        }

    override fun accessibleSnippets(
        userId: String,
        relation: RelationFilter,
    ): List<SnippetAccess> =
        upstream(SERVICE) {
            client
                .get()
                .uri("/permissions/snippets?userId={userId}&relation={relation}", userId, relation.value)
                .retrieve()
                .body<AccessibleSnippets>()
        }.snippets

    override fun revokeAll(snippetId: UUID) {
        upstream(SERVICE) {
            client
                .delete()
                .uri("/permissions/snippets/{snippetId}", snippetId)
                .retrieve()
                .toBodilessEntity()
        }
    }

    private data class Permission(
        val snippetId: UUID,
        val userId: String,
        val role: Role,
    )

    private data class AccessibleSnippets(
        val snippets: List<SnippetAccess>,
    )

    companion object {
        const val SERVICE = "Permission Service"
    }
}
