package org.snippet.snippet.snippet

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.snippet.snippet.clients.permission.RelationFilter
import org.snippet.snippet.common.security.CurrentUser
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** `/snippets` of `contracts/snippet-service.openapi.yaml` (CRUD, listing, sharing and download). */
@RestController
@RequestMapping("/snippets")
class SnippetController(
    private val service: SnippetService,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(name = "page_size", defaultValue = "10") @Min(1) @Max(100) pageSize: Int,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) language: String?,
        @RequestParam(required = false) compliance: Compliance?,
        @RequestParam(defaultValue = "all") relation: RelationFilter,
        @RequestParam(defaultValue = "name") sort: SortField,
        @RequestParam(defaultValue = "asc") order: SortOrder,
    ): SnippetPage =
        service.list(
            CurrentUser.from(jwt),
            SnippetQuery(page, pageSize, SnippetFilter(name, language, compliance), relation, sort, order),
        )

    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateSnippetRequest,
    ): ResponseEntity<SnippetResponse> {
        val snippet = service.create(CurrentUser.from(jwt), request)
        val location =
            ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(snippet.id)
                .toUri()
        return ResponseEntity.created(location).body(snippet)
    }

    @GetMapping("/{snippetId}")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
    ): SnippetResponse = service.get(CurrentUser.from(jwt), snippetId)

    @PutMapping("/{snippetId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @Valid @RequestBody request: UpdateSnippetRequest,
    ): SnippetResponse = service.update(CurrentUser.from(jwt), snippetId, request)

    @DeleteMapping("/{snippetId}")
    fun delete(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
    ): ResponseEntity<Unit> {
        service.delete(CurrentUser.from(jwt), snippetId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{snippetId}/share")
    fun share(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @Valid @RequestBody request: ShareSnippetRequest,
    ): SnippetResponse = service.share(CurrentUser.from(jwt), snippetId, request.userId)

    @GetMapping("/{snippetId}/download")
    fun download(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @RequestParam(defaultValue = "false") formatted: Boolean,
    ): ResponseEntity<String> {
        val file = service.download(CurrentUser.from(jwt), snippetId, formatted)
        return ResponseEntity
            .ok()
            .contentType(MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, attachment(file.filename).toString())
            .body(file.content)
    }

    // Plain `filename="..."` when it's ASCII (as in the contract); otherwise Spring adds `filename*` (RFC 5987)
    private fun attachment(filename: String): ContentDisposition {
        val ascii = filename.all { it.code in 0x20..0x7e }
        return ContentDisposition
            .attachment()
            .filename(filename, if (ascii) null else StandardCharsets.UTF_8)
            .build()
    }
}
