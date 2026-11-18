package org.snippet.snippet.rules

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.snippet.Compliance
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetService.Companion.CONTENT
import org.snippet.snippet.snippet.StoredViolation
import org.snippet.snippet.support.FakeRunner
import org.snippet.snippet.support.ProcessingFixture
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleServiceTest {
    private val fixture = ProcessingFixture()
    private val service = RuleService(fixture.ruleStore, fixture.rules, fixture.runner, fixture.jobs)

    private fun snippet(owner: String): Snippet =
        Snippet(UUID.randomUUID(), "s", null, "printscript", "1.1", "ps", owner, owner, Compliance.COMPLIANT).also {
            it.lintViolations = mutableListOf(StoredViolation("r", "old", 1, 1))
            fixture.repository.save(it)
            fixture.assets.put(CONTENT, it.id.toString(), "println(\"$owner\");")
        }

    @Test
    fun `without saved rules the user gets the runner's defaults, with the name as id`() {
        val rules = service.get("auth0|alice", RuleType.LINT)

        assertEquals(listOf(Rule("identifier_format", "identifier_format", true, "camel case")), rules)
        assertEquals("indentation", service.get("auth0|alice", RuleType.FORMAT).single().id)
    }

    @Test
    fun `saved rules replace the defaults, per user and type`() {
        service.update("auth0|alice", RuleType.LINT, listOf(RuleInput(name = "identifier_format", isActive = false)))

        assertEquals(listOf(Rule("identifier_format", "identifier_format", false, null)), service.get("auth0|alice", RuleType.LINT))
        assertEquals("camel case", service.get("auth0|bob", RuleType.LINT).single().value)
        assertEquals(4, service.get("auth0|alice", RuleType.FORMAT).single().value)
    }

    @Test
    fun `saving lint rules sends the owner's snippets to pending and publishes a LINT each`() {
        val mine = listOf(snippet("auth0|alice"), snippet("auth0|alice"))
        val bobs = snippet("auth0|bob")

        val saved = service.update("auth0|alice", RuleType.LINT, listOf(RuleInput("x", "identifier_format", true, "snake case")))

        assertEquals(listOf(Rule("identifier_format", "identifier_format", true, "snake case")), saved)
        mine.forEach {
            assertEquals(
                Compliance.PENDING,
                fixture.repository.snippets
                    .getValue(it.id)
                    .compliance,
            )
            assertTrue(
                fixture.repository.snippets
                    .getValue(it.id)
                    .lintViolations
                    .isEmpty(),
            )
            val job = fixture.jobsOf(it.id, "LINT").single()
            assertEquals("snake case", job["rules"][0]["value"].asText())
            assertEquals("println(\"auth0|alice\");", job["content"].asText())
        }
        assertEquals(
            Compliance.COMPLIANT,
            fixture.repository.snippets
                .getValue(bobs.id)
                .compliance,
        )
        assertTrue(fixture.jobsOf(bobs.id, "LINT").isEmpty())
    }

    @Test
    fun `saving format rules publishes a FORMAT per snippet of the owner, without touching compliance`() {
        val mine = snippet("auth0|alice")

        service.update("auth0|alice", RuleType.FORMAT, listOf(RuleInput(name = "indentation", isActive = true, value = 2)))

        assertEquals(2, fixture.jobsOf(mine.id, "FORMAT").single()["rules"][0]["value"].asInt())
        assertEquals(
            Compliance.COMPLIANT,
            fixture.repository.snippets
                .getValue(mine.id)
                .compliance,
        )
        assertEquals(listOf(RuleConfig("indentation", true, 2)), fixture.rules.find("auth0|alice", RuleType.FORMAT))
    }

    @Test
    fun `invalid rules are rejected and nothing is saved or published`() {
        snippet("auth0|alice")
        val invalid =
            listOf(
                listOf(RuleInput(name = "no-such-rule", isActive = true)),
                listOf(RuleInput(name = "identifier_format", isActive = true), RuleInput(name = "identifier_format", isActive = false)),
                listOf(RuleInput(name = "identifier_format", isActive = true, value = FakeRunner.BAD_VALUE)),
                listOf(RuleInput(name = "identifier_format", isActive = true, value = listOf("a"))),
            )

        invalid.forEach { assertThrows<BadRequestException> { service.update("auth0|alice", RuleType.LINT, it) } }

        assertTrue(fixture.rules.rules.isEmpty())
        assertTrue(fixture.publishedJobs().isEmpty())
    }
}
