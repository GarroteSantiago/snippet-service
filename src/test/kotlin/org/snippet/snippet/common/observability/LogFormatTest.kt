package org.snippet.snippet.common.observability

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.logging.LoggingInitializationContext
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `logback-spring.xml` with each `logging.console-format`. Logback is configured once per JVM,
 * so each test configures it again with its own environment and leaves it plain afterwards.
 */
@ExtendWith(OutputCaptureExtension::class)
class LogFormatTest {
    private val system = LoggingSystem.get(javaClass.classLoader)

    private fun configure(format: String) {
        val environment =
            MockEnvironment()
                .withProperty("spring.application.name", "snippet-service")
                .withProperty("logging.console-format", format)
                .withProperty("logging.structured.json.add.service", "snippet-service")
        system.cleanUp()
        system.beforeInitialize()
        system.initialize(LoggingInitializationContext(environment), null, null)
    }

    private fun logWithMdc(message: String) {
        MDC.put("correlationId", "corr-log")
        MDC.put("user", "auth0|alice")
        try {
            LoggerFactory.getLogger(javaClass).info(message)
        } finally {
            MDC.clear()
        }
    }

    @AfterEach
    fun restore() = configure("plain")

    @Test
    fun `json has one object per line with the service and the MDC`(output: CapturedOutput) {
        configure("json")
        logWithMdc("structured hello")

        val log = ObjectMapper().readTree(output.out.lines().last { it.contains("structured hello") })
        assertEquals("structured hello", log["message"].asText())
        assertEquals("INFO", log["level"].asText())
        assertEquals("corr-log", log["correlationId"].asText())
        assertEquals("auth0|alice", log["user"].asText())
        assertEquals("snippet-service", log["service"].asText())
        assertTrue(log.has("@timestamp"))
    }

    @Test
    fun `plain keeps the readable line`(output: CapturedOutput) {
        configure("plain")
        logWithMdc("plain hello")

        val line = output.out.lines().last { it.contains("plain hello") }
        assertTrue(line.contains("[snippet-service,corr-log,auth0|alice]"), line)
    }
}
