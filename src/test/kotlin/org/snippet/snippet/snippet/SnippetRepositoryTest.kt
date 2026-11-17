package org.snippet.snippet.snippet

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The Flyway schema, the jsonb/enum mapping and the listing query, against a real Postgres. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Needs Docker for Postgres: skipped where there is none, runs in CI.
@Testcontainers(disabledWithoutDocker = true)
class SnippetRepositoryTest(
    @param:Autowired private val repository: SnippetRepository,
    @param:Autowired private val entityManager: TestEntityManager,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private fun snippet(
        name: String,
        language: String = "printscript",
        compliance: Compliance = Compliance.PENDING,
    ) = Snippet(
        id = UUID.randomUUID(),
        name = name,
        description = null,
        language = language,
        version = "1.1",
        extension = "ps",
        ownerId = "auth0|alice",
        ownerName = "Alice",
        compliance = compliance,
    )

    private fun saveAll(vararg snippets: Snippet): List<Snippet> {
        snippets.forEach { repository.save(it) }
        entityManager.flush()
        entityManager.clear()
        return snippets.toList()
    }

    private fun names(
        ids: Collection<UUID>,
        filter: SnippetFilter = SnippetFilter(),
        sort: Sort = Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id")),
        page: Int = 0,
        size: Int = 10,
    ) = repository
        .findAll(SnippetSpecs.accessible(ids, filter), PageRequest.of(page, size, sort))
        .content
        .map { it.name }

    @Test
    fun `stores compliance with its contract value and lint violations as json`() {
        val saved = snippet("hello", compliance = Compliance.NOT_COMPLIANT)
        saved.lintViolations = mutableListOf(StoredViolation("no-snake-case", "Use camelCase", 2, 5))
        saveAll(saved)

        val column = entityManager.entityManager.createNativeQuery("select compliance from snippet where id = ?1")
        column.setParameter(1, saved.id)
        assertEquals("not-compliant", column.singleResult)

        val loaded = assertNotNull(repository.findByIdOrNull(saved.id))
        assertEquals(Compliance.NOT_COMPLIANT, loaded.compliance)
        assertEquals(listOf(StoredViolation("no-snake-case", "Use camelCase", 2, 5)), loaded.lintViolations)
        assertEquals("Alice", loaded.ownerName)
    }

    @Test
    fun `only returns the given ids`() {
        val (a, _, c) = saveAll(snippet("a"), snippet("b"), snippet("c"))

        assertEquals(listOf("a", "c"), names(listOf(a.id, c.id)))
    }

    @Test
    fun `filters by name ignoring case, and literally`() {
        val all = saveAll(snippet("Hello World"), snippet("say hello"), snippet("bye"), snippet("100% done"), snippet("1000 done"))
        val ids = all.map { it.id }

        assertEquals(listOf("Hello World", "say hello"), names(ids, SnippetFilter(name = "HELLO")))
        assertEquals(listOf("100% done"), names(ids, SnippetFilter(name = "0%")))
    }

    @Test
    fun `filters by language and compliance`() {
        val all =
            saveAll(
                snippet("a", compliance = Compliance.COMPLIANT),
                snippet("b", compliance = Compliance.NOT_COMPLIANT),
                snippet("c", language = "otherscript", compliance = Compliance.COMPLIANT),
            )
        val ids = all.map { it.id }

        assertEquals(listOf("a", "c"), names(ids, SnippetFilter(compliance = Compliance.COMPLIANT)))
        assertEquals(listOf("a", "b"), names(ids, SnippetFilter(language = "printscript")))
        assertEquals(listOf("a"), names(ids, SnippetFilter(language = "printscript", compliance = Compliance.COMPLIANT)))
    }

    @Test
    fun `sorts and paginates`() {
        val all = saveAll(snippet("b"), snippet("C"), snippet("a"), snippet("D"))
        val ids = all.map { it.id }

        assertEquals(listOf("a", "b"), names(ids, page = 0, size = 2))
        assertEquals(listOf("C", "D"), names(ids, page = 1, size = 2))
        assertEquals(listOf("D", "C", "b", "a"), names(ids, sort = Sort.by(Sort.Order.desc("name").ignoreCase())))

        val page = repository.findAll(SnippetSpecs.accessible(ids, SnippetFilter()), PageRequest.of(0, 3))
        assertEquals(4, page.totalElements)
    }
}
