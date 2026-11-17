package org.snippet.snippet.format

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.language.LanguageCatalog
import org.snippet.snippet.language.LanguageProperties
import org.snippet.snippet.support.FakeRunner
import kotlin.test.assertEquals

class FormatServiceTest {
    private val runner = FakeRunner()
    private val service = FormatService(runner, LanguageCatalog(runner, LanguageProperties()))

    @Test
    fun `formats with the runner's default format rules`() {
        assertEquals("println(1);\n", service.format(FormatRequest("  println(1);  ", "printscript", "1.1")))
        assertEquals(runner.formatRules, runner.lastFormatRules)
    }

    @Test
    fun `rejects an unsupported version and invalid code`() {
        assertThrows<BadRequestException> { service.format(FormatRequest("println(1);", "printscript", "7.0")) }
        assertThrows<InvalidCodeException> { service.format(FormatRequest("!!", "printscript", "1.1")) }
    }
}
