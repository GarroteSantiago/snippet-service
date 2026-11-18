package org.snippet.snippet.language

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.common.security.SecurityConfig
import org.snippet.snippet.format.FormatController
import org.snippet.snippet.format.FormatRequest
import org.snippet.snippet.format.FormatService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(controllers = [LanguageController::class, FormatController::class])
@Import(SecurityConfig::class)
@ActiveProfiles("test")
class LanguageAndFormatControllerTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var catalog: LanguageCatalog

    @MockitoBean
    private lateinit var formatService: FormatService

    @Test
    fun `lists the supported languages`() {
        given(catalog.all()).willReturn(listOf(FileType("printscript", "ps", listOf("1.0", "1.1"))))

        mockMvc.get("/languages") { with(jwt()) }.andExpect {
            status { isOk() }
            jsonPath("$[0].language") { value("printscript") }
            jsonPath("$[0].extension") { value("ps") }
            jsonPath("$[0].versions[1]") { value("1.1") }
        }
    }

    @Test
    fun `formats content`() {
        given(formatService.format(FormatRequest("let x=1;", "printscript", "1.1"), "user")).willReturn("let x = 1;\n")

        mockMvc
            .post("/format") {
                with(jwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"let x=1;","language":"printscript","version":"1.1"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.content") { value("let x = 1;\n") }
            }
    }

    @Test
    fun `format of invalid code is 422 with diagnostics`() {
        given(formatService.format(FormatRequest("!!", "printscript", "1.1"), "user"))
            .willThrow(InvalidCodeException(listOf(Diagnostic(null, "Unexpected", 1, 1))))

        mockMvc
            .post("/format") {
                with(jwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"!!","language":"printscript","version":"1.1"}"""
            }.andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.diagnostics[0].message") { value("Unexpected") }
            }
    }

    @Test
    fun `format without a version is 400`() {
        mockMvc
            .post("/format") {
                with(jwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"x","language":"printscript"}"""
            }.andExpect { status { isBadRequest() } }
    }
}
