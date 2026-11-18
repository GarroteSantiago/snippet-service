package org.snippet.snippet.testcase

import org.slf4j.LoggerFactory
import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.permission.Action
import org.snippet.snippet.clients.permission.PermissionClient
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.clients.runner.TestStatus
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.security.CurrentUser
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetRepository
import org.snippet.snippet.snippet.authorize
import org.snippet.snippet.snippet.contentOf
import org.snippet.snippet.snippet.findOrNotFound
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Tests of a snippet (US-08, US-09). Same access rules as the snippet: listing and
 * running need `READ`, creating, editing and deleting need `WRITE` (the `OWNER`).
 * A test that belongs to another snippet is 404, like one that doesn't exist.
 */
@Service
class TestCaseService(
    private val repository: TestCaseRepository,
    private val snippets: SnippetRepository,
    private val permissions: PermissionClient,
    private val runner: RunnerClient,
    private val assets: AssetClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(
        user: CurrentUser,
        snippetId: UUID,
    ): List<TestCaseResponse> {
        permissions.authorize(user, snippetId, Action.READ)
        snippets.findOrNotFound(snippetId)
        return testsOfSnippet(snippetId).map { it.toResponse() }
    }

    /** Tests that fail are saved too: the result is only known when it runs. */
    fun create(
        user: CurrentUser,
        snippetId: UUID,
        request: TestCaseRequest,
    ): TestCaseResponse {
        permissions.authorize(user, snippetId, Action.WRITE)
        snippets.findOrNotFound(snippetId)
        val test =
            TestCase(
                id = UUID.randomUUID(),
                snippetId = snippetId,
                name = request.name,
                inputs = request.input.toMutableList(),
                expectedOutputs = request.output.toMutableList(),
            )
        return repository.save(test).toResponse()
    }

    /** Replaces the whole test. The last result was for the old test, so it's cleared. */
    fun update(
        user: CurrentUser,
        snippetId: UUID,
        testId: UUID,
        request: TestCaseRequest,
    ): TestCaseResponse {
        permissions.authorize(user, snippetId, Action.WRITE)
        val test = find(snippetId, testId)
        test.name = request.name
        test.inputs = request.input.toMutableList()
        test.expectedOutputs = request.output.toMutableList()
        test.lastResult = null
        test.lastRunAt = null
        test.updatedAt = Instant.now()
        return repository.save(test).toResponse()
    }

    fun delete(
        user: CurrentUser,
        snippetId: UUID,
        testId: UUID,
    ) {
        permissions.authorize(user, snippetId, Action.WRITE)
        repository.delete(find(snippetId, testId))
    }

    /** Runs a saved test (US-09) and keeps its result as `lastResult`. `READ` is enough. */
    fun run(
        user: CurrentUser,
        snippetId: UUID,
        testId: UUID,
    ): TestRunResult {
        permissions.authorize(user, snippetId, Action.READ)
        val snippet = snippets.findOrNotFound(snippetId)
        val test = find(snippetId, testId)
        val result = run(snippet, test.inputs, test.expectedOutputs)
        recordResult(test.id, result.status)
        return result
    }

    /** Runs the inputs and outputs of [request] against the snippet without saving anything. */
    fun runAdHoc(
        user: CurrentUser,
        snippetId: UUID,
        request: TestCaseRequest,
    ): TestRunResult {
        permissions.authorize(user, snippetId, Action.READ)
        val snippet = snippets.findOrNotFound(snippetId)
        return run(snippet, request.input, request.output)
    }

    /**
     * Every test of the snippet, without checking permissions: for the background runs
     * of US-16 (one `TEST` job per test when the code changes).
     */
    fun testsOfSnippet(snippetId: UUID): List<TestCase> = repository.findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId)

    /**
     * Saves [status] as the last result of a test, without checking permissions: used by
     * the saved-test run and by the consumer of the US-16 `TEST` results. Only the result
     * columns change. Returns false if the test no longer exists (deleted, or its snippet
     * was), which is not an error for a result that arrives late.
     */
    fun recordResult(
        testId: UUID,
        status: TestStatus,
        runAt: Instant = Instant.now(),
    ): Boolean {
        val updated = repository.updateResult(testId, status, runAt) > 0
        if (!updated) log.info("Test {} is gone; its result ({}) is dropped", testId, status.value)
        return updated
    }

    private fun run(
        snippet: Snippet,
        inputs: List<String>,
        expectedOutputs: List<String>,
    ): TestRunResult {
        val result = runner.test(assets.contentOf(snippet), snippet.version, inputs, expectedOutputs)
        return TestRunResult(result.status, result.outputs, result.errors)
    }

    private fun find(
        snippetId: UUID,
        testId: UUID,
    ): TestCase =
        repository.findByIdAndSnippetId(testId, snippetId)
            ?: throw NotFoundException("Test $testId not found in snippet $snippetId")

    private fun TestCase.toResponse() = TestCaseResponse(id, name, inputs.toList(), expectedOutputs.toList(), lastResult)
}
