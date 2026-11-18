package org.snippet.snippet.testcase

import org.junit.jupiter.api.Test
import org.snippet.snippet.clients.runner.TestStatus
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.repository.findByIdOrNull
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `test_case` schema, its jsonb/status mapping, the queries and the cascade, against a real Postgres. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Needs Docker for Postgres: skipped where there is none, runs in CI.
@Testcontainers(disabledWithoutDocker = true)
class TestCaseRepositoryTest(
    @param:Autowired private val repository: TestCaseRepository,
    @param:Autowired private val snippets: SnippetRepository,
    @param:Autowired private val entityManager: TestEntityManager,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private fun snippet(): Snippet =
        snippets.save(
            Snippet(
                id = UUID.randomUUID(),
                name = "echo",
                description = null,
                language = "printscript",
                version = "1.1",
                extension = "ps",
                ownerId = "auth0|alice",
                ownerName = "Alice",
            ),
        )

    private fun test(
        snippetId: UUID,
        name: String,
        createdAt: Instant = Instant.now(),
    ) = repository.save(
        TestCase(
            id = UUID.randomUUID(),
            snippetId = snippetId,
            name = name,
            inputs = mutableListOf("a", "b"),
            expectedOutputs = mutableListOf("b"),
            createdAt = createdAt,
        ),
    )

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    private fun column(
        name: String,
        id: UUID,
    ): Any? =
        entityManager.entityManager
            .createNativeQuery("select $name from test_case where id = ?1")
            .setParameter(1, id)
            .singleResult

    @Test
    fun `stores inputs and outputs as json and starts without a result`() {
        val saved = test(snippet().id, "echoes")
        flushAndClear()

        val loaded = assertNotNull(repository.findByIdOrNull(saved.id))
        assertEquals(listOf("a", "b"), loaded.inputs)
        assertEquals(listOf("b"), loaded.expectedOutputs)
        assertNull(loaded.lastResult)
        assertNull(loaded.lastRunAt)
    }

    @Test
    fun `lists a snippet's tests in creation order and finds them only under their snippet`() {
        val snippet = snippet()
        val other = snippet()
        val now = Instant.parse("2026-11-17T10:00:00Z")
        val second = test(snippet.id, "second", now.plusSeconds(1))
        val first = test(snippet.id, "first", now)
        test(other.id, "elsewhere")
        flushAndClear()

        assertEquals(listOf("first", "second"), repository.findBySnippetIdOrderByCreatedAtAscIdAsc(snippet.id).map { it.name })
        assertEquals("first", repository.findByIdAndSnippetId(first.id, snippet.id)?.name)
        assertNull(repository.findByIdAndSnippetId(second.id, other.id))
    }

    @Test
    fun `updates only the result, stored with its contract value`() {
        val saved = test(snippet().id, "echoes")
        flushAndClear()
        val at = Instant.parse("2026-11-17T10:00:00Z")

        assertEquals(1, repository.updateResult(saved.id, TestStatus.SUCCESS, at))
        assertEquals(0, repository.updateResult(UUID.randomUUID(), TestStatus.FAIL, at))

        assertEquals("success", column("last_result", saved.id))
        val loaded = assertNotNull(repository.findByIdOrNull(saved.id))
        assertEquals(TestStatus.SUCCESS, loaded.lastResult)
        assertEquals(at, loaded.lastRunAt)
        assertEquals("echoes", loaded.name)
    }

    @Test
    fun `deleting the snippet deletes its tests`() {
        val snippet = snippet()
        val other = snippet()
        test(snippet.id, "a")
        test(snippet.id, "b")
        val kept = test(other.id, "c")
        flushAndClear()

        snippets.deleteById(snippet.id)
        flushAndClear()

        assertTrue(repository.findBySnippetIdOrderByCreatedAtAscIdAsc(snippet.id).isEmpty())
        assertEquals(listOf(kept.id), repository.findAll().map { it.id })
    }
}
