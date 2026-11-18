package org.snippet.snippet.processing

import org.junit.jupiter.api.Test
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.common.security.CurrentUser
import org.snippet.snippet.language.LanguageCatalog
import org.snippet.snippet.language.LanguageProperties
import org.snippet.snippet.snippet.Compliance
import org.snippet.snippet.snippet.CreateSnippetRequest
import org.snippet.snippet.snippet.SnippetService
import org.snippet.snippet.snippet.UpdateSnippetRequest
import org.snippet.snippet.support.FakePermissions
import org.snippet.snippet.support.FakeUsers
import org.snippet.snippet.support.ProcessingFixture
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the snippet CRUD publishes to `snippet:jobs`. */
class SnippetJobsTest {
    private val fixture = ProcessingFixture()
    private val service =
        SnippetService(
            fixture.repository,
            FakePermissions(),
            fixture.runner,
            fixture.assets,
            LanguageCatalog(fixture.runner, LanguageProperties()),
            fixture.jobs,
            FakeUsers(),
        )
    private val alice = CurrentUser("auth0|alice", "Alice")

    private fun create(content: String = "println(1);") =
        service.create(alice, CreateSnippetRequest("hello", null, "printscript", "1.1", "ps", content)).id

    @Test
    fun `creating a snippet publishes a LINT with the owner's rules and records its job id`() {
        val mine = listOf(RuleConfig("identifier_format", true, "snake case"))
        fixture.rules.save(alice.id, RuleType.LINT, mine)

        val id = create()

        val (type, payload) = fixture.publishedJobs().single()
        assertEquals("LINT", type)
        assertEquals(id.toString(), payload["snippetId"].asText())
        assertEquals("1.1", payload["version"].asText())
        assertEquals("println(1);", payload["content"].asText())
        assertEquals("snake case", payload["rules"][0]["value"].asText())
        assertEquals(UUID.fromString(payload["jobId"].asText()), fixture.tracker.latest(id, JobType.LINT))
        assertEquals(
            Compliance.PENDING,
            fixture.repository.snippets
                .getValue(id)
                .compliance,
        )
    }

    @Test
    fun `jobs carry a correlation id`() {
        create()

        assertTrue(
            fixture.streams
                .entries(Streams.JOBS)
                .single()
                .fields[Streams.CORRELATION_ID]
                .orEmpty()
                .isNotBlank(),
        )
    }

    @Test
    fun `a snippet whose LINT can't be published is failed, not pending forever`() {
        fixture.streams.failAdd = true

        val created = service.create(alice, CreateSnippetRequest("x", null, "printscript", "1.1", "ps", "println(1);"))

        assertEquals(Compliance.FAILED, created.compliance)
        assertEquals(
            Compliance.FAILED,
            fixture.repository.snippets
                .getValue(created.id)
                .compliance,
        )
    }

    @Test
    fun `changing the content publishes LINT and FORMAT with the new content`() {
        val id = create()

        service.update(alice, id, UpdateSnippetRequest(content = "println(2);"))

        assertEquals(listOf("LINT", "LINT", "FORMAT"), fixture.publishedJobs().map { it.first })
        assertEquals("println(2);", fixture.jobsOf(id, "FORMAT").single()["content"].asText())
        assertEquals("indentation", fixture.jobsOf(id, "FORMAT").single()["rules"][0]["name"].asText())
        assertEquals("println(2);", fixture.jobsOf(id, "LINT").last()["content"].asText())
    }

    @Test
    fun `changing the version alone also reprocesses`() {
        val id = create()

        service.update(alice, id, UpdateSnippetRequest(version = "1.0"))

        assertEquals("1.0", fixture.jobsOf(id, "FORMAT").single()["version"].asText())
    }

    @Test
    fun `changing only the name publishes nothing`() {
        val id = create()

        service.update(alice, id, UpdateSnippetRequest(name = "renamed"))

        assertEquals(1, fixture.publishedJobs().size)
    }

    @Test
    fun `a LINT forgets a pending VALIDATE and the other way around`() {
        val id = create()
        val snippet = fixture.repository.snippets.getValue(id)

        fixture.jobs.validate(snippet, "println(1);")
        assertNull(fixture.tracker.latest(id, JobType.LINT))

        fixture.jobs.lint(snippet, "println(1);")
        assertNull(fixture.tracker.latest(id, JobType.VALIDATE))
    }

    @Test
    fun `validateAll publishes a VALIDATE per snippet and fails the ones without content`() {
        val a = create()
        val b = create()
        fixture.assets.delete(SnippetService.CONTENT, b.toString())

        fixture.jobs.validateAll()

        assertEquals(1, fixture.jobsOf(a, "VALIDATE").size)
        assertTrue(fixture.jobsOf(b, "VALIDATE").isEmpty())
        assertEquals(
            Compliance.FAILED,
            fixture.repository.snippets
                .getValue(b)
                .compliance,
        )
        assertNull(fixture.jobsOf(a, "VALIDATE").single()["rules"])
    }
}
