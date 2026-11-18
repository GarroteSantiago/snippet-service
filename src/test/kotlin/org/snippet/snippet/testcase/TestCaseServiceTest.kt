package org.snippet.snippet.testcase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.clients.runner.TestStatus
import org.snippet.snippet.common.error.ForbiddenException
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.security.CurrentUser
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetService.Companion.CONTENT
import org.snippet.snippet.support.FakeAssets
import org.snippet.snippet.support.FakePermissions
import org.snippet.snippet.support.FakeRunner
import org.snippet.snippet.support.InMemorySnippetRepository
import org.snippet.snippet.support.InMemoryTestCaseRepository
import org.snippet.snippet.support.TestCall
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestCaseServiceTest {
    private val repository = InMemoryTestCaseRepository()
    private val snippets = InMemorySnippetRepository()
    private val permissions = FakePermissions()
    private val runner = FakeRunner()
    private val assets = FakeAssets()
    private val service = TestCaseService(repository, snippets, permissions, runner, assets)

    private val alice = CurrentUser("auth0|alice", "Alice")
    private val bob = CurrentUser("auth0|bob", "Bob")
    private val carol = CurrentUser("auth0|carol", "Carol")

    // Alice owns it and shares it with Bob; Carol has no access
    private val snippet =
        Snippet(
            id = UUID.randomUUID(),
            name = "echo",
            description = null,
            language = "printscript",
            version = "1.1",
            extension = "ps",
            ownerId = alice.id,
            ownerName = alice.name,
        ).also {
            snippets.save(it)
            assets.put(CONTENT, it.id.toString(), "println(readInput(\"\"));")
            permissions.roles[it.id to alice.id] = Role.OWNER
            permissions.roles[it.id to bob.id] = Role.READ
        }

    private fun create(
        name: String = "echoes",
        input: List<String> = listOf("a", "b"),
        output: List<String> = listOf("a", "b"),
    ) = service.create(alice, snippet.id, TestCaseRequest(name, input, output))

    @Test
    fun `the owner creates tests, even ones that fail, and they start without a result`() {
        val passing = create()
        val failing = create("wrong", output = listOf("x"))

        assertNull(passing.lastResult)
        assertEquals(listOf("a", "b"), passing.input)
        assertEquals(listOf("x"), failing.output)
        val stored = repository.tests.getValue(passing.id)
        assertEquals(snippet.id, stored.snippetId)
        assertEquals(listOf("a", "b"), stored.expectedOutputs)
    }

    @Test
    fun `only the owner creates, edits and deletes tests`() {
        val test = create()
        val request = TestCaseRequest("other")

        assertThrows<ForbiddenException> { service.create(bob, snippet.id, request) }
        assertThrows<ForbiddenException> { service.update(bob, snippet.id, test.id, request) }
        assertThrows<ForbiddenException> { service.delete(bob, snippet.id, test.id) }
        assertThrows<NotFoundException> { service.create(carol, snippet.id, request) }
        assertThrows<NotFoundException> { service.delete(carol, snippet.id, test.id) }
        assertEquals(listOf("echoes"), repository.tests.values.map { it.name })
    }

    @Test
    fun `reading users list the tests, users without access get 404`() {
        create("first")
        create("second")

        assertEquals(listOf("first", "second"), service.list(bob, snippet.id).map { it.name })
        assertThrows<NotFoundException> { service.list(carol, snippet.id) }
        assertThrows<NotFoundException> { service.list(alice, UUID.randomUUID()) }
    }

    @Test
    fun `a permission left over from a deleted snippet is 404`() {
        snippets.delete(snippet)

        assertThrows<NotFoundException> { service.list(alice, snippet.id) }
        assertThrows<NotFoundException> { service.create(alice, snippet.id, TestCaseRequest("x")) }
    }

    @Test
    fun `update replaces the test and clears its last result`() {
        val test = create()
        service.recordResult(test.id, TestStatus.SUCCESS)

        val updated = service.update(alice, snippet.id, test.id, TestCaseRequest("renamed", listOf("1"), listOf("2")))

        assertEquals("renamed", updated.name)
        assertEquals(listOf("1"), updated.input)
        assertEquals(listOf("2"), updated.output)
        assertNull(updated.lastResult)
        assertNull(repository.tests.getValue(test.id).lastRunAt)
    }

    @Test
    fun `a test of another snippet is 404`() {
        val test = create()
        val other = UUID.randomUUID()
        permissions.roles[other to alice.id] = Role.OWNER

        assertThrows<NotFoundException> { service.update(alice, other, test.id, TestCaseRequest("x")) }
        assertThrows<NotFoundException> { service.delete(alice, other, test.id) }
        assertThrows<NotFoundException> { service.run(alice, other, test.id) }
        assertThrows<NotFoundException> { service.delete(alice, snippet.id, UUID.randomUUID()) }
    }

    @Test
    fun `deletes a test`() {
        val test = create()

        service.delete(alice, snippet.id, test.id)

        assertTrue(repository.tests.isEmpty())
    }

    @Test
    fun `running a saved test sends the content, version, inputs and outputs, and keeps the result`() {
        val test = create(output = listOf("a", "c"))

        val result = service.run(bob, snippet.id, test.id)

        assertEquals(TestCall("println(readInput(\"\"));", "1.1", listOf("a", "b"), listOf("a", "c")), runner.lastTest)
        assertEquals(TestStatus.FAIL, result.status)
        assertEquals(listOf("a", "b"), result.outputs)
        val stored = repository.tests.getValue(test.id)
        assertEquals(TestStatus.FAIL, stored.lastResult)
        assertNotNull(stored.lastRunAt)
        assertEquals(TestStatus.FAIL, service.list(alice, snippet.id).single().lastResult)
    }

    @Test
    fun `a run that errors comes back as fail with the errors`() {
        val test = create()
        assets.put(CONTENT, snippet.id.toString(), "println(!!);")

        val result = service.run(alice, snippet.id, test.id)

        assertEquals(TestStatus.FAIL, result.status)
        assertEquals(listOf(FakeRunner.DIAGNOSTIC), result.errors)
    }

    @Test
    fun `an ad hoc run saves nothing`() {
        val result = service.runAdHoc(bob, snippet.id, TestCaseRequest("try", listOf("x"), listOf("x")))

        assertEquals(TestStatus.SUCCESS, result.status)
        assertEquals(listOf("x"), result.outputs)
        assertTrue(repository.tests.isEmpty())
        assertThrows<NotFoundException> { service.runAdHoc(carol, snippet.id, TestCaseRequest("try")) }
    }

    @Test
    fun `missing content is an upstream error and records nothing`() {
        val test = create()
        assets.delete(CONTENT, snippet.id.toString())

        assertThrows<UpstreamException> { service.run(alice, snippet.id, test.id) }
        assertNull(repository.tests.getValue(test.id).lastResult)
    }

    @Test
    fun `background runs read the tests and record results by test id`() {
        val test = create()
        val at = Instant.parse("2026-11-17T10:00:00Z")

        assertEquals(listOf(test.id), service.testsOfSnippet(snippet.id).map { it.id })
        assertTrue(service.recordResult(test.id, TestStatus.SUCCESS, at))
        assertEquals(TestStatus.SUCCESS, repository.tests.getValue(test.id).lastResult)
        assertEquals(at, repository.tests.getValue(test.id).lastRunAt)
        assertFalse(service.recordResult(UUID.randomUUID(), TestStatus.FAIL))
    }
}
