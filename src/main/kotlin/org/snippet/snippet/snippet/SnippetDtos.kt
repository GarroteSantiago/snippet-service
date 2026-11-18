package org.snippet.snippet.snippet

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.snippet.snippet.clients.permission.RelationFilter
import org.snippet.snippet.clients.runner.Diagnostic
import java.time.Instant
import java.util.UUID

data class CreateSnippetRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val name: String,
    @field:Size(max = 1000)
    val description: String? = null,
    @field:NotBlank
    val language: String,
    @field:NotBlank
    val version: String,
    @field:NotBlank
    val extension: String,
    val content: String,
)

/** Every field is optional: only what comes is changed. */
data class UpdateSnippetRequest(
    @field:Size(min = 1, max = 120)
    @field:Pattern(regexp = ".*\\S.*", message = "must not be blank")
    val name: String? = null,
    @field:Size(max = 1000)
    val description: String? = null,
    @field:Size(min = 1)
    val language: String? = null,
    @field:Size(min = 1)
    val version: String? = null,
    @field:Size(min = 1)
    val extension: String? = null,
    val content: String? = null,
)

/** Body of `POST /snippets/{snippetId}/share`: the Auth0 user id (`sub`) to share with. */
data class ShareSnippetRequest(
    @field:NotBlank
    val userId: String,
)

/** The caller's relation with a snippet, from their role in the Permission Service. */
enum class Relation(
    @get:JsonValue val value: String,
) {
    OWNED("owned"),
    SHARED("shared"),
}

/** `Snippet` in the contract. `content` and `lintViolations` only come in the detail. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SnippetResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val language: String,
    val version: String,
    val extension: String,
    val content: String? = null,
    val compliance: Compliance,
    val author: String,
    val authorId: String,
    val relation: Relation,
    val lintViolations: List<Diagnostic>? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SnippetPage(
    val page: Int,
    @get:JsonProperty("page_size")
    val pageSize: Int,
    val count: Long,
    val snippets: List<SnippetResponse>,
)

enum class SortField(
    val value: String,
    val property: String,
) {
    NAME("name", "name"),
    LANGUAGE("language", "language"),
    COMPLIANCE("compliance", "compliance"),
}

enum class SortOrder(
    val value: String,
) {
    ASC("asc"),
    DESC("desc"),
}

data class SnippetQuery(
    val page: Int = 0,
    val pageSize: Int = 10,
    val filter: SnippetFilter = SnippetFilter(),
    val relation: RelationFilter = RelationFilter.ALL,
    val sort: SortField = SortField.NAME,
    val order: SortOrder = SortOrder.ASC,
)

/** A snippet as a file to download. */
data class SnippetFile(
    val filename: String,
    val content: String,
)
