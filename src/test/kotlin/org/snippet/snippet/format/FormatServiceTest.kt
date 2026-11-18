package org.snippet.snippet.format

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.language.LanguageCatalog
import org.snippet.snippet.language.LanguageProperties
import org.snippet.snippet.rules.RuleStore
import org.snippet.snippet.support.FakeRunner
import org.snippet.snippet.support.InMemoryRuleRepository
import kotlin.test.assertEquals

class FormatServiceTest {
    private val runner = FakeRunner()
    private val rules = InMemoryRuleRepository()
    private val service = FormatService(runner, LanguageCatalog(runner, LanguageProperties()), RuleStore(rules, runner))

    @Test
    fun `formats with the runner's default format rules when the user has none`() {
        assertEquals("println(1);\n", service.format(FormatRequest("  println(1);  ", "printscript", "1.1"), "auth0|alice"))
        assertEquals(runner.formatRules.map { it.copy(description = null) }, runner.lastFormatRules)
    }

    @Test
    fun `formats with the caller's own format rules`() {
        val mine = listOf(RuleConfig("indentation", true, 2))
        rules.save("auth0|alice", RuleType.FORMAT, mine)

        service.format(FormatRequest("println(1);", "printscript", "1.1"), "auth0|alice")

        assertEquals(mine, runner.lastFormatRules)
    }

    @Test
    fun `rejects an unsupported version and invalid code`() {
        assertThrows<BadRequestException> { service.format(FormatRequest("println(1);", "printscript", "7.0"), "u") }
        assertThrows<InvalidCodeException> { service.format(FormatRequest("!!", "printscript", "1.1"), "u") }
    }
}
