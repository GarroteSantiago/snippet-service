package org.snippet.snippet.snippet

import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.asset.RestAssetClient
import org.snippet.snippet.clients.permission.Action
import org.snippet.snippet.clients.permission.PermissionClient
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.common.error.ForbiddenException
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.security.CurrentUser
import org.springframework.data.repository.findByIdOrNull
import java.util.UUID

// Shared by everything that acts on one snippet (CRUD, tests), so the access rules are the same everywhere.

/**
 * The caller's role on the snippet, if they may do [action] on it. Pass [token] when there
 * is no HTTP request to take it from (WebSocket threads). No permission at all
 * is 404, the same as for a snippet that doesn't exist, so ids don't leak; a `READ`
 * user asking for `WRITE` gets 403.
 */
fun PermissionClient.authorize(
    user: CurrentUser,
    snippetId: UUID,
    action: Action,
    token: String? = null,
): Role {
    val check = check(snippetId, user.id, action, token)
    return when {
        check.allowed -> check.role ?: if (action == Action.WRITE) Role.OWNER else Role.READ
        check.role == null -> throw snippetNotFound(snippetId)
        else -> throw ForbiddenException("Only the owner can change snippet $snippetId")
    }
}

/** 404 if the metadata is gone (a permission can outlive it for a moment while deleting). */
fun SnippetRepository.findOrNotFound(id: UUID): Snippet = findByIdOrNull(id) ?: throw snippetNotFound(id)

/** The original content. A snippet always has one, so a missing one is the Asset Service's fault (502). */
fun AssetClient.contentOf(
    snippet: Snippet,
    token: String? = null,
): String =
    get(SnippetService.CONTENT, snippet.id.toString(), token)
        ?: throw UpstreamException(RestAssetClient.SERVICE, "content of snippet ${snippet.id} is missing")

fun snippetNotFound(id: UUID) = NotFoundException("Snippet $id not found")
