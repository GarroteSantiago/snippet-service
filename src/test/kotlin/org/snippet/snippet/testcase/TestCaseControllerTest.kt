package org.snippet.snippet.testcase

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.clients.runner.TestStatus
import org.snippet.snippet.common.error.ForbiddenException
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.security.CurrentUser
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID

@WebMvcTest(controllers = [TestCaseController::class])
@Import(SecurityConfig::class)
@ActiveProfiles("test")
class TestCaseControllerTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var service: TestCaseService

    private val snippetId = UUID.fromString("6f1c2b8e-3d4a-4f7e-9a1b-2c3d4e5f6a7b")
    private val testId = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
    private val alice = CurrentUser("auth0|alice", "Alice")
    private val aliceToken = jwt().jwt { it.subject("auth0|alice").claim("name", "Alice") }
    private val base = "/snippets/$snippetId/tests"

    private val request = TestCaseRequest("echoes", listOf("a"), listOf("a"))
    private val test = TestCaseResponse(testId, "echoes", listOf("a"), listOf("a"), null)

    @Test
    fun `needs a token`() {
        mockMvc.get(base).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `lists tests, with a null last result when they never ran`() {
        given(service.list(alice, snippetId)).willReturn(listOf(test, test.copy(lastResult = TestStatus.FAIL)))

        mockMvc.get(base) { with(aliceToken) }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(testId.toString()) }
            jsonPath("$[0].name") { value("echoes") }
            jsonPath("$[0].input[0]") { value("a") }
            jsonPath("$[0].output[0]") { value("a") }
            // Present and null, as the contract says
            content { string(containsString("""{"id":"$testId","name":"echoes","input":["a"],"output":["a"],"lastResult":null}""")) }
            jsonPath("$[1].lastResult") { value("fail") }
        }
    }

    @Test
    fun `creates a test with 201`() {
        given(service.create(alice, snippetId, request)).willReturn(test)

        mockMvc
            .post(base) {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"echoes","input":["a"],"output":["a"]}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(testId.toString()) }
            }
    }

    @Test
    fun `input and output are optional`() {
        given(service.create(alice, snippetId, TestCaseRequest("empty"))).willReturn(test)

        mockMvc
            .post(base) {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"empty"}"""
            }.andExpect { status { isCreated() } }
    }

    @Test
    fun `rejects a test without a name with 400`() {
        listOf("""{"input":["a"]}""", """{"name":"  "}""", """{"name":"x","input":"a"}""").forEach { body ->
            mockMvc
                .post(base) {
                    with(aliceToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect {
                    status { isBadRequest() }
                    content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                }
        }
        verifyNoInteractions(service)
    }

    @Test
    fun `updates a test`() {
        given(service.update(alice, snippetId, testId, request)).willReturn(test)

        mockMvc
            .put("$base/$testId") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"echoes","input":["a"],"output":["a"]}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.name") { value("echoes") }
            }
    }

    @Test
    fun `deletes with 204`() {
        mockMvc.delete("$base/$testId") { with(aliceToken) }.andExpect { status { isNoContent() } }

        verify(service).delete(alice, snippetId, testId)
    }

    @Test
    fun `runs a saved test`() {
        given(service.run(alice, snippetId, testId))
            .willReturn(TestRunResult(TestStatus.FAIL, listOf("b"), listOf(Diagnostic(null, "Boom", 1, 2))))

        mockMvc.post("$base/$testId/run") { with(aliceToken) }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("fail") }
            jsonPath("$.outputs[0]") { value("b") }
            jsonPath("$.errors[0].message") { value("Boom") }
        }
    }

    @Test
    fun `runs an ad hoc test`() {
        given(service.runAdHoc(alice, snippetId, request)).willReturn(TestRunResult(TestStatus.SUCCESS, listOf("a"), emptyList()))

        mockMvc
            .post("$base/run") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"echoes","input":["a"],"output":["a"]}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("success") }
            }
    }

    @Test
    fun `errors come out as problem details`() {
        given(service.list(alice, snippetId)).willThrow(NotFoundException("Snippet $snippetId not found"))
        given(service.update(alice, snippetId, testId, request)).willThrow(ForbiddenException())
        given(service.run(alice, snippetId, testId)).willThrow(UpstreamException("PrintScript Runner", "answered 500"))

        mockMvc.get(base) { with(aliceToken) }.andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
        mockMvc
            .put("$base/$testId") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"echoes","input":["a"],"output":["a"]}"""
            }.andExpect { status { isForbidden() } }
        mockMvc.post("$base/$testId/run") { with(aliceToken) }.andExpect { status { isBadGateway() } }
    }

    @Test
    fun `ids that aren't uuids are 400`() {
        mockMvc.delete("$base/not-a-uuid") { with(aliceToken) }.andExpect { status { isBadRequest() } }
        mockMvc.get("/snippets/not-a-uuid/tests") { with(aliceToken) }.andExpect { status { isBadRequest() } }
    }
}
