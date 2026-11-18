package org.snippet.snippet.support

import org.mockito.Mockito.mock
import org.snippet.snippet.clients.runner.TestStatus
import org.snippet.snippet.testcase.TestCase
import org.snippet.snippet.testcase.TestCaseRepository
import java.time.Instant
import java.util.UUID

/** Keeps tests in a map, for the service tests. The real queries are covered by `TestCaseRepositoryTest`. */
class InMemoryTestCaseRepository(
    unused: TestCaseRepository = mock(TestCaseRepository::class.java),
) : TestCaseRepository by unused {
    val tests = linkedMapOf<UUID, TestCase>()

    override fun <S : TestCase> save(entity: S): S {
        tests[entity.id] = entity
        return entity
    }

    override fun delete(entity: TestCase) {
        tests.remove(entity.id)
    }

    override fun findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId: UUID) = tests.values.filter { it.snippetId == snippetId }

    override fun findByIdAndSnippetId(
        id: UUID,
        snippetId: UUID,
    ) = tests[id]?.takeIf { it.snippetId == snippetId }

    override fun updateResult(
        id: UUID,
        result: TestStatus,
        runAt: Instant,
    ): Int {
        val test = tests[id] ?: return 0
        test.lastResult = result
        test.lastRunAt = runAt
        return 1
    }
}
