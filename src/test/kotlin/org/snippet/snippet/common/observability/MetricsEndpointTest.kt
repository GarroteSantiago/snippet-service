package org.snippet.snippet.common.observability

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** `/prometheus` without a token only on the internal metrics port; `/health` as before. */
@SpringBootTest(properties = ["observability.metrics-port=$METRICS_PORT"])
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
// Needs Docker for Postgres: skipped where there is none, runs in CI.
@Testcontainers(disabledWithoutDocker = true)
class MetricsEndpointTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private val onMetricsPort = RequestPostProcessor { it.apply { localPort = METRICS_PORT } }

    @Test
    fun `prometheus answers without a token on the metrics port`() {
        mockMvc.get("/health")
        mockMvc.get("/prometheus") { with(onMetricsPort) }.andExpect {
            status { isOk() }
            content { string(containsString("http_server_requests_seconds")) }
            content { string(containsString("jvm_threads_live_threads")) }
        }
    }

    @Test
    fun `prometheus is not served on the main port, not even with a token`() {
        mockMvc.get("/prometheus").andExpect { status { isUnauthorized() } }
        mockMvc.get("/prometheus") { with(jwt()) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `health stays public on the main port`() {
        mockMvc.get("/health").andExpect { status { isOk() } }
    }
}

private const val METRICS_PORT = 9464
