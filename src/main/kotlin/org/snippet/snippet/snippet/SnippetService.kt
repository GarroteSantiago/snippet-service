package org.snippet.snippet.snippet

import org.slf4j.LoggerFactory
import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.asset.RestAssetClient
import org.snippet.snippet.clients.permission.Action
import org.snippet.snippet.clients.permission.PermissionClient
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.common.error.ForbiddenException
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.security.CurrentUser
import org.snippet.snippet.language.LanguageCatalog
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Snippet CRUD. Orchestrates the Runner (validation), the Asset Service (content),
 * the Permission Service (who can do what) and the `snippet` table (metadata).
 *
 * Access: a user with no permission at all on a snippet gets 404, the same as for a
 * snippet that doesn't exist, so ids don't leak. A `READ` user asking for `WRITE` gets 403.
 */
@Service
class SnippetService(
    private val repository: SnippetRepository,
    private val permissions: PermissionClient,
    private val runner: RunnerClient,
    private val assets: AssetClient,
    private val languages: LanguageCatalog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Validates, then stores metadata, content and the `OWNER` permission, in that order.
     * The permission is what makes the snippet visible, so it goes last; if any step
     * fails, the previous ones are undone and the original error is rethrown.
     */
    fun create(
        user: CurrentUser,
        request: CreateSnippetRequest,
    ): SnippetResponse {
        languages.requireSupported(request.language, request.version, request.extension)
        requireValid(request.content, request.version)

        val snippet =
            Snippet(
                id = UUID.randomUUID(),
                name = request.name,
                description = request.description,
                language = request.language,
                version = request.version,
                extension = request.extension,
                ownerId = user.id,
                ownerName = user.name,
            )
        repository.save(snippet)
        try {
            assets.put(CONTENT, snippet.key, request.content)
            permissions.grant(snippet.id, user.id, Role.OWNER)
        } catch (e: Exception) {
            log.warn("Creating snippet {} failed, undoing it", snippet.id)
            // The grant may have gone through even if we didn't get the answer
            undo("permissions of ${snippet.id}") { permissions.revokeAll(snippet.id) }
            undo("content of ${snippet.id}") { assets.delete(CONTENT, snippet.key) }
            undo("metadata of ${snippet.id}") { repository.deleteById(snippet.id) }
            throw e
        }
        // TODO(etapa 6): publicar el pedido de LINT en Redis; compliance queda en pending hasta el resultado
        return snippet.toResponse(Role.OWNER, request.content)
    }

    fun get(
        user: CurrentUser,
        id: UUID,
    ): SnippetResponse {
        val role = authorize(user, id, Action.READ)
        val snippet = find(id)
        return snippet.toResponse(role, content(snippet))
    }

    /**
     * Changes only the fields that come. New content (or a new version for the old
     * content) is validated first and nothing is changed if it's invalid; when the code
     * changes the snippet goes back to `pending`.
     */
    fun update(
        user: CurrentUser,
        id: UUID,
        request: UpdateSnippetRequest,
    ): SnippetResponse {
        val role = authorize(user, id, Action.WRITE)
        val snippet = find(id)

        val language = request.language ?: snippet.language
        val version = request.version ?: snippet.version
        val extension = request.extension ?: snippet.extension
        if (request.language != null || request.version != null || request.extension != null) {
            languages.requireSupported(language, version, extension)
        }
        val codeChanged = request.content != null || version != snippet.version
        val content = request.content ?: content(snippet)
        if (codeChanged) requireValid(content, version)

        val previous = snippet.snapshot()
        request.name?.let { snippet.name = it }
        request.description?.let { snippet.description = it }
        snippet.language = language
        snippet.version = version
        snippet.extension = extension
        if (codeChanged) {
            snippet.compliance = Compliance.PENDING
            snippet.lintViolations = mutableListOf()
        }
        snippet.updatedAt = Instant.now()
        repository.save(snippet)

        if (request.content != null) {
            try {
                assets.put(CONTENT, snippet.key, request.content)
            } catch (e: Exception) {
                // The old content is still there: put the old metadata back too
                undo("metadata of ${snippet.id}") { repository.save(snippet.restore(previous)) }
                throw e
            }
        }
        // TODO(etapa 6): si cambió el código, publicar LINT y FORMAT en Redis (la versión formateada queda vieja)
        // TODO(etapa 7): si cambió el código, correr los tests del snippet en segundo plano (US-16)
        return snippet.toResponse(role, content)
    }

    /**
     * Deleting the metadata is what deletes the snippet for everyone. Content and
     * permissions are cleaned up after that; a failure there is only logged, because
     * a leftover blob or permission is unreachable without the metadata.
     */
    fun delete(
        user: CurrentUser,
        id: UUID,
    ) {
        authorize(user, id, Action.WRITE)
        val snippet = find(id)
        repository.delete(snippet)
        // TODO(etapa 7): borrar los tests del snippet (o dejar que los borre la FK en cascada)
        undo("content of $id") { assets.delete(CONTENT, snippet.key) }
        undo("formatted content of $id") { assets.delete(FORMATTED, snippet.key) }
        undo("permissions of $id") { permissions.revokeAll(id) }
    }

    /** The Permission Service says which snippets the user can see; filtering, sorting and paging happen in Postgres. */
    fun list(
        user: CurrentUser,
        query: SnippetQuery,
    ): SnippetPage {
        val roles = permissions.accessibleSnippets(user.id, query.relation).associate { it.snippetId to it.role }
        if (roles.isEmpty()) return SnippetPage(query.page, query.pageSize, 0, emptyList())

        val direction = if (query.order == SortOrder.DESC) Sort.Direction.DESC else Sort.Direction.ASC
        val sortBy = Sort.Order(direction, query.sort.property).let { if (query.sort == SortField.NAME) it.ignoreCase() else it }
        // The id breaks ties, so pages don't overlap
        val pageable = PageRequest.of(query.page, query.pageSize, Sort.by(sortBy, Sort.Order.asc("id")))
        val page = repository.findAll(SnippetSpecs.accessible(roles.keys, query.filter), pageable)
        return SnippetPage(
            page = query.page,
            pageSize = query.pageSize,
            count = page.totalElements,
            snippets = page.content.map { it.toResponse(roles.getValue(it.id)) },
        )
    }

    /** The original content, or the formatted one if [formatted] (stored by the formatting of stage 6). */
    fun download(
        user: CurrentUser,
        id: UUID,
        formatted: Boolean,
    ): SnippetFile {
        authorize(user, id, Action.READ)
        val snippet = find(id)
        val content =
            if (formatted) {
                assets.get(FORMATTED, snippet.key) ?: throw NotFoundException("Snippet $id has no formatted version yet")
            } else {
                content(snippet)
            }
        return SnippetFile("${snippet.name}.${snippet.extension}", content)
    }

    /**
     * What the Runner needs to run the snippet interactively (US-10), if [user] can read it. Called
     * from a WebSocket thread, which has no request: the calls carry [token] explicitly.
     */
    fun runnable(
        user: CurrentUser,
        id: UUID,
        token: String,
    ): RunnableSnippet {
        authorize(user, id, Action.READ, token)
        val snippet = find(id)
        return RunnableSnippet(content(snippet, token), snippet.version)
    }

    private fun authorize(
        user: CurrentUser,
        id: UUID,
        action: Action,
        token: String? = null,
    ): Role {
        val check = permissions.check(id, user.id, action, token)
        return when {
            check.allowed -> check.role ?: if (action == Action.WRITE) Role.OWNER else Role.READ
            check.role == null -> throw notFound(id)
            else -> throw ForbiddenException("Only the owner can change snippet $id")
        }
    }

    private fun find(id: UUID): Snippet = repository.findByIdOrNull(id) ?: throw notFound(id)

    private fun notFound(id: UUID) = NotFoundException("Snippet $id not found")

    private fun content(
        snippet: Snippet,
        token: String? = null,
    ): String =
        assets.get(CONTENT, snippet.key, token)
            ?: throw UpstreamException(RestAssetClient.SERVICE, "content of snippet ${snippet.id} is missing")

    private fun requireValid(
        content: String,
        version: String,
    ) {
        val result = runner.validate(content, version)
        if (!result.valid) throw InvalidCodeException(result.diagnostics)
    }

    /** Runs a cleanup step; a failure is logged so it doesn't hide the error that caused it. */
    private fun undo(
        what: String,
        step: () -> Unit,
    ) {
        try {
            step()
        } catch (e: Exception) {
            log.error("Could not clean up {}; it's left orphaned", what, e)
        }
    }

    private fun Snippet.toResponse(
        role: Role,
        content: String? = null,
    ) = SnippetResponse(
        id = id,
        name = name,
        description = description,
        language = language,
        version = version,
        extension = extension,
        content = content,
        compliance = compliance,
        author = ownerName,
        authorId = ownerId,
        relation = if (role == Role.OWNER) Relation.OWNED else Relation.SHARED,
        // Only in the detail, which is the one that comes with the content
        lintViolations = content?.let { lintViolations.map { Diagnostic(it.rule, it.message, it.line, it.column) } },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        /** Asset Service containers. The key is the snippet id. */
        const val CONTENT = "snippets"
        const val FORMATTED = "snippets-formatted"
    }
}

private val Snippet.key: String get() = id.toString()

/** The fields an update can change, to put them back if storing the content fails. */
private data class SnippetSnapshot(
    val name: String,
    val description: String?,
    val language: String,
    val version: String,
    val extension: String,
    val compliance: Compliance,
    val lintViolations: List<StoredViolation>,
    val updatedAt: Instant,
)

private fun Snippet.snapshot() =
    SnippetSnapshot(name, description, language, version, extension, compliance, lintViolations.toList(), updatedAt)

private fun Snippet.restore(snapshot: SnippetSnapshot): Snippet =
    apply {
        name = snapshot.name
        description = snapshot.description
        language = snapshot.language
        version = snapshot.version
        extension = snapshot.extension
        compliance = snapshot.compliance
        lintViolations = snapshot.lintViolations.toMutableList()
        updatedAt = snapshot.updatedAt
    }
