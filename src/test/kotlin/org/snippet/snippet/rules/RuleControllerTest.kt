package org.snippet.snippet.rules

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.common.security.SecurityConfig
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
import org.springframework.test.web.servlet.put

@WebMvcTest(controllers = [RuleController::class])
@Import(SecurityConfig::class)
@ActiveProfiles("test")
class RuleControllerTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var service: RuleService

    private val alice = jwt().jwt { it.subject("auth0|alice") }

    @Test
    fun `returns the caller's rules`() {
        given(service.get("auth0|alice", RuleType.FORMAT)).willReturn(listOf(Rule("indent-inside-if", "indent-inside-if", true, 4)))

        mockMvc.get("/rules/format") { with(alice) }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value("indent-inside-if") }
            jsonPath("$[0].name") { value("indent-inside-if") }
            jsonPath("$[0].isActive") { value(true) }
            jsonPath("$[0].value") { value(4) }
        }
    }

    @Test
    fun `saving answers 202 with the saved rules`() {
        val input = listOf(RuleInput("identifier_format", "identifier_format", true, "snake case"))
        given(service.update("auth0|alice", RuleType.LINT, input))
            .willReturn(listOf(Rule("identifier_format", "identifier_format", true, "snake case")))

        mockMvc
            .put("/rules/lint") {
                with(alice)
                contentType = MediaType.APPLICATION_JSON
                content = """[{"id":"identifier_format","name":"identifier_format","isActive":true,"value":"snake case"}]"""
            }.andExpect {
                status { isAccepted() }
                jsonPath("$[0].value") { value("snake case") }
            }
    }

    @Test
    fun `invalid rules and unknown types are 400`() {
        given(service.update("auth0|alice", RuleType.LINT, listOf(RuleInput(null, "nope", true))))
            .willThrow(BadRequestException("Unknown lint rule 'nope'"))

        mockMvc
            .put("/rules/lint") {
                with(alice)
                contentType = MediaType.APPLICATION_JSON
                content = """[{"name":"nope","isActive":true}]"""
            }.andExpect { status { isBadRequest() } }
        mockMvc
            .put("/rules/lint") {
                with(alice)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"not a list"}"""
            }.andExpect { status { isBadRequest() } }
        mockMvc.get("/rules/style") { with(alice) }.andExpect { status { isBadRequest() } }
        mockMvc.get("/rules/lint").andExpect { status { isUnauthorized() } }
    }
}
