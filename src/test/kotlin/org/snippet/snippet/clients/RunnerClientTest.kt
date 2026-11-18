package org.snippet.snippet.clients

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.clients.runner.RestRunnerClient
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.common.error.UpstreamException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RunnerClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = RestRunnerClient(builder, "http://runner")

    @Test
    fun `reads the supported versions`() {
        server
            .expect(requestTo("http://runner/versions"))
            .andRespond(withSuccess("""{"language":"printscript","versions":["1.0","1.1"],"latest":"1.1"}""", MediaType.APPLICATION_JSON))

        val versions = client.versions()

        assertEquals("printscript", versions.language)
        assertEquals(listOf("1.0", "1.1"), versions.versions)
        server.verify()
    }

    @Test
    fun `invalid code is a normal answer with diagnostics`() {
        server
            .expect(requestTo("http://runner/validate"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"content":"let x","version":"1.1"}""", JsonCompareMode.STRICT))
            .andRespond(
                withSuccess(
                    """{"valid":false,"diagnostics":[{"rule":null,"message":"Expected ';'","line":1,"column":6}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.validate("let x", "1.1")

        assertFalse(result.valid)
        assertEquals(listOf(Diagnostic(null, "Expected ';'", 1, 6)), result.diagnostics)
    }

    @Test
    fun `reads the default rules of a type`() {
        server
            .expect(requestTo("http://runner/rules/format"))
            .andRespond(
                withSuccess(
                    """[{"name":"indentation","isActive":true,"value":4,"description":"Spaces"}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(listOf(RuleConfig("indentation", true, 4, "Spaces")), client.defaultRules(RuleType.FORMAT))
    }

    @Test
    fun `formats with the given rules, without their descriptions`() {
        server
            .expect(requestTo("http://runner/format"))
            .andExpect(
                content().json(
                    """{"content":"println(1);","version":"1.1","rules":[{"name":"indentation","isActive":true,"value":4}]}""",
                    JsonCompareMode.STRICT,
                ),
            ).andRespond(withSuccess("""{"content":"println(1);\n"}""", MediaType.APPLICATION_JSON))

        val formatted = client.format("println(1);", "1.1", listOf(RuleConfig("indentation", true, 4, "Spaces")))

        assertEquals("println(1);\n", formatted)
    }

    @Test
    fun `a 422 from format becomes invalid code with the runner's diagnostics`() {
        server
            .expect(requestTo("http://runner/format"))
            .andRespond(
                withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("""{"status":422,"diagnostics":[{"rule":null,"message":"Unexpected","line":2,"column":1}]}"""),
            )

        val error = assertThrows<InvalidCodeException> { client.format("!!", "1.1", emptyList()) }

        assertEquals(listOf(Diagnostic(null, "Unexpected", 2, 1)), error.diagnostics)
    }

    @Test
    fun `other errors are upstream errors`() {
        server.expect(requestTo("http://runner/versions")).andRespond(withServerError())

        assertThrows<UpstreamException> { client.versions() }
    }

    @Test
    fun `reads the parser version`() {
        server
            .expect(requestTo("http://runner/versions"))
            .andRespond(
                withSuccess(
                    """{"language":"printscript","versions":["1.1"],"latest":"1.1","parserVersion":"2.0.0"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals("2.0.0", client.versions().parserVersion)
    }

    @Test
    fun `rules are checked by running them over a probe, and a 400 is a bad request with the runner's reason`() {
        server
            .expect(requestTo("http://runner/lint"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """{"content":"println(1);","version":"1.1","rules":[{"name":"identifier_format","isActive":true,"value":"x"}]}""",
                    JsonCompareMode.STRICT,
                ),
            ).andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("""{"status":400,"detail":"Rule 'identifier_format' must be one of 'camel case'"}"""),
            )
        server.expect(requestTo("http://runner/format")).andRespond(withSuccess("""{"content":""}""", MediaType.APPLICATION_JSON))

        val error =
            assertThrows<BadRequestException> {
                client.validateRules(RuleType.LINT, listOf(RuleConfig("identifier_format", true, "x")), "1.1")
            }
        assertEquals("Rule 'identifier_format' must be one of 'camel case'", error.message)
        client.validateRules(RuleType.FORMAT, listOf(RuleConfig("indentation", true, 2)), "1.1")
        server.verify()
    }
}
