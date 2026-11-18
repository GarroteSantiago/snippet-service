package org.snippet.snippet.support

import org.mockito.Mockito.mock
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import java.util.Optional
import java.util.UUID

/**
 * Keeps snippets in a map, for the service tests. The criteria query of the listing
 * can't run in memory: [findAll] returns every snippet and remembers the [Pageable];
 * the real query is covered by `SnippetRepositoryTest` against Postgres.
 */
class InMemorySnippetRepository(
    unused: SnippetRepository = mock(SnippetRepository::class.java),
) : SnippetRepository by unused {
    val snippets = linkedMapOf<UUID, Snippet>()
    var failSave = false
    var lastPageable: Pageable? = null

    override fun <S : Snippet> save(entity: S): S {
        if (failSave) throw IllegalStateException("database down")
        snippets[entity.id] = entity
        return entity
    }

    override fun findById(id: UUID): Optional<Snippet> = Optional.ofNullable(snippets[id])

    override fun findAllByOwnerId(ownerId: String): List<Snippet> = snippets.values.filter { it.ownerId == ownerId }

    override fun findAll(): List<Snippet> = snippets.values.toList()

    override fun deleteById(id: UUID) {
        snippets.remove(id)
    }

    override fun delete(entity: Snippet) {
        snippets.remove(entity.id)
    }

    override fun findAll(
        spec: Specification<Snippet>?,
        pageable: Pageable,
    ): Page<Snippet> {
        lastPageable = pageable
        return PageImpl(snippets.values.toList(), pageable, snippets.size.toLong())
    }
}
