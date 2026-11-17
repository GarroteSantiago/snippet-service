package org.snippet.snippet.language

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.runner.Versions
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.support.FakeRunner
import kotlin.test.assertEquals

class LanguageCatalogTest {
    private val runner = FakeRunner()
    private val catalog = LanguageCatalog(runner, LanguageProperties())

    @Test
    fun `lists the runner's language with its configured extension`() {
        assertEquals(listOf(FileType("printscript", "ps", listOf("1.0", "1.1"))), catalog.all())
    }

    @Test
    fun `an unconfigured language falls back to its name as extension`() {
        runner.versions = Versions("otherscript", listOf("2.0"), "2.0")

        assertEquals("otherscript", catalog.all().single().extension)
    }

    @Test
    fun `accepts supported combinations and rejects the rest with 400`() {
        assertDoesNotThrow { catalog.requireSupported("printscript", "1.0", "ps") }
        assertDoesNotThrow { catalog.requireSupported("printscript", "1.1") }
        assertThrows<BadRequestException> { catalog.requireSupported("python", "1.1") }
        assertThrows<BadRequestException> { catalog.requireSupported("printscript", "2.0") }
        assertThrows<BadRequestException> { catalog.requireSupported("printscript", "1.1", "txt") }
    }
}
