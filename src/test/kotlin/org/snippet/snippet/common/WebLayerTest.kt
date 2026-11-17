package org.snippet.snippet.common

import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.logging.CorrelationIdFilter
import org.snippet.snippet.common.logging.UserMdcFilter
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Checks the cross-cutting web setup every endpoint gets: auth, MDC context and error format. */
@WebMvcTest(controllers = [ProbeController::class])
@Import(SecurityConfig::class, ProbeController::class)
@ActiveProfiles("test")
class WebLayerTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `rejects requests without a token`() {
        mockMvc.get("/test/probe").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `keeps the user and the incoming correlation id in the MDC`() {
        mockMvc
            .get("/test/probe") {
                with(jwt().jwt { it.subject("auth0|alice") })
                header(CorrelationIdFilter.HEADER, "abc-123")
            }.andExpect {
                status { isOk() }
                header { string(CorrelationIdFilter.HEADER, "abc-123") }
                jsonPath("$.user") { value("auth0|alice") }
                jsonPath("$.correlationId") { value("abc-123") }
            }
    }

    @Test
    fun `creates a correlation id when the request has none`() {
        mockMvc
            .get("/test/probe") { with(jwt()) }
            .andExpect {
                status { isOk() }
                header { exists(CorrelationIdFilter.HEADER) }
            }
    }

    @Test
    fun `replaces a correlation id that is not safe to log`() {
        mockMvc
            .get("/test/probe") {
                with(jwt())
                header(CorrelationIdFilter.HEADER, "bad id\nFAKE LOG LINE")
            }.andExpect {
                header { string(CorrelationIdFilter.HEADER, matchesPattern("^[0-9a-f-]{36}$")) }
            }
    }

    @Test
    fun `renders api errors as problem details`() {
        mockMvc
            .get("/test/missing") { with(jwt()) }
            .andExpect {
                status { isNotFound() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(404) }
                jsonPath("$.detail") { value("nothing here") }
            }
    }
}

@RestController
class ProbeController {
    @GetMapping("/test/probe")
    fun probe(): Map<String, String?> =
        mapOf(
            "user" to MDC.get(UserMdcFilter.MDC_KEY),
            "correlationId" to MDC.get(CorrelationIdFilter.MDC_KEY),
        )

    @GetMapping("/test/missing")
    fun missing(): Nothing = throw NotFoundException("nothing here")
}
