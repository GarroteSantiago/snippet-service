package org.snippet.snippet

import org.junit.jupiter.api.Test
import org.snippet.snippet.common.http.ServiceTokenProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Starts the whole application. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Needs Docker for Postgres: skipped where there is none, runs in CI.
@Testcontainers(disabledWithoutDocker = true)
class ApplicationIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val serviceToken: ServiceTokenProperties,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    @Test
    fun `health is public and up`() {
        mockMvc.get("/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `everything else needs a token`() {
        mockMvc.get("/anything").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the service token comes from the same issuer and audience by default`() {
        assertEquals("https://snippet-searcher.test/oauth/token", serviceToken.tokenUri)
        assertEquals("https://snippet-searcher.test/api", serviceToken.audience)
        assertFalse(serviceToken.configured)
    }
}
