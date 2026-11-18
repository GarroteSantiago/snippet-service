package org.snippet.snippet.snippet

import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface SnippetRepository :
    JpaRepository<Snippet, UUID>,
    JpaSpecificationExecutor<Snippet> {
    fun findAllByOwnerId(ownerId: String): List<Snippet>
}

/** Filters of the listing (US-05). Null means "don't filter". */
data class SnippetFilter(
    val name: String? = null,
    val language: String? = null,
    val compliance: Compliance? = null,
)

object SnippetSpecs {
    /** Snippets among [ids] that match [filter]; the name matches if it contains the text, ignoring case. */
    fun accessible(
        ids: Collection<UUID>,
        filter: SnippetFilter,
    ): Specification<Snippet> =
        Specification { root, _, cb ->
            val predicates =
                buildList {
                    add(root.get<UUID>("id").`in`(ids))
                    filter.name?.takeIf { it.isNotBlank() }?.let {
                        add(cb.like(cb.lower(root.get("name")), "%${escapeLike(it.lowercase())}%", '\\'))
                    }
                    filter.language?.let { add(cb.equal(root.get<String>("language"), it)) }
                    filter.compliance?.let { add(cb.equal(root.get<Compliance>("compliance"), it)) }
                }
            cb.and(*predicates.toTypedArray())
        }

    // So `%` and `_` typed by the user are matched literally
    private fun escapeLike(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
