package org.snippet.snippet.clients

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.clients.runner.RestRunnerClient
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.clients.runner.TestResult
import org.snippet.snippet.clients.runner.TestStatus
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
    fun `runs a test with its inputs and expected outputs`() {
        server
            .expect(requestTo("http://runner/test"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """{"content":"println(readInput(\"\"));","version":"1.1","inputs":["a"],"expectedOutputs":["b"]}""",
                    JsonCompareMode.STRICT,
                ),
            ).andRespond(withSuccess("""{"status":"fail","outputs":["a"]}""", MediaType.APPLICATION_JSON))

        val result = client.test("println(readInput(\"\"));", "1.1", listOf("a"), listOf("b"))

        assertEquals(TestResult(TestStatus.FAIL, listOf("a"), emptyList()), result)
    }

    @Test
    fun `a test run keeps the runner's errors`() {
        server
            .expect(requestTo("http://runner/test"))
            .andRespond(
                withSuccess(
                    """{"status":"fail","outputs":[],"errors":[{"rule":null,"message":"Boom","line":1,"column":2}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.test("x", "1.1", emptyList(), emptyList())

        assertEquals(listOf(Diagnostic(null, "Boom", 1, 2)), result.errors)
    }

    @Test
    fun `a test status outside the contract or a 400 is an upstream error`() {
        val unknownStatus = """{"status":"error","outputs":[]}"""
        server.expect(requestTo("http://runner/test")).andRespond(withSuccess(unknownStatus, MediaType.APPLICATION_JSON))
        server.expect(requestTo("http://runner/test")).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertThrows<UpstreamException> { client.test("x", "1.1", emptyList(), emptyList()) }
        assertThrows<UpstreamException> { client.test("x", "1.1", emptyList(), emptyList()) }
    }

    @Test
    fun `other errors are upstream errors`() {
        server.expect(requestTo("http://runner/versions")).andRespond(withServerError())

        assertThrows<UpstreamException> { client.versions() }
    }
}
