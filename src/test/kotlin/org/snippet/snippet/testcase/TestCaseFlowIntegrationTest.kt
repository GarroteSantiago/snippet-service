package org.snippet.snippet.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.support.FakeAssets
import org.snippet.snippet.support.FakePermissions
import org.snippet.snippet.support.FakeRunner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertTrue

/** Tests of a snippet through HTTP, with Postgres and in-memory versions of the other services. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Needs Docker for Postgres: skipped where there is none, runs in CI.
@Testcontainers(disabledWithoutDocker = true)
class TestCaseFlowIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val objectMapper: ObjectMapper,
    @param:Autowired private val permissions: FakePermissions,
    @param:Autowired private val repository: TestCaseRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    // Picked up by @SpringBootTest because it's nested; @Primary wins over the HTTP clients
    @TestConfiguration
    class FakeServices {
        @Bean
        @Primary
        fun fakeRunner(): RunnerClient = FakeRunner()

        @Bean
        @Primary
        fun fakePermissions(): FakePermissions = FakePermissions()

        @Bean
        @Primary
        fun fakeAssets(): FakeAssets = FakeAssets()
    }

    private val alice = jwt().jwt { it.subject("auth0|alice") }
    private val bob = jwt().jwt { it.subject("auth0|bob") }

    private fun idOf(body: String): UUID = UUID.fromString(objectMapper.readTree(body)["id"].asText())

    private fun createSnippet(): UUID =
        idOf(
            mockMvc
                .post("/snippets") {
                    with(alice)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"echo","language":"printscript","version":"1.1","extension":"ps","content":"println(1);"}"""
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString,
        )

    @Test
    fun `create, list, run, edit and delete tests, and they go with the snippet`() {
        val snippet = createSnippet()
        permissions.roles[snippet to "auth0|bob"] = Role.READ
        val tests = "/snippets/$snippet/tests"

        val test =
            idOf(
                mockMvc
                    .post(tests) {
                        with(alice)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"echoes","input":["a"],"output":["b"]}"""
                    }.andExpect {
                        status { isCreated() }
                        content { string(containsString("\"lastResult\":null")) }
                    }.andReturn()
                    .response.contentAsString,
            )
        mockMvc
            .post(tests) {
                with(bob)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"mine"}"""
            }.andExpect { status { isForbidden() } }

        // The fake runner prints the inputs back, so this one fails
        mockMvc.post("$tests/$test/run") { with(bob) }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("fail") }
            jsonPath("$.outputs[0]") { value("a") }
        }
        mockMvc.get(tests) { with(bob) }.andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("echoes") }
            jsonPath("$[0].lastResult") { value("fail") }
        }
        mockMvc
            .post("$tests/run") {
                with(bob)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"try","input":["x"],"output":["x"]}"""
            }.andExpect { jsonPath("$.status") { value("success") } }

        mockMvc
            .put("$tests/$test") {
                with(alice)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"echoes","input":["b"],"output":["b"]}"""
            }.andExpect {
                status { isOk() }
                content { string(containsString("\"lastResult\":null")) }
            }
        mockMvc.post("$tests/$test/run") { with(alice) }.andExpect { jsonPath("$.status") { value("success") } }

        val second =
            idOf(
                mockMvc
                    .post(tests) {
                        with(alice)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"name":"second"}"""
                    }.andReturn()
                    .response.contentAsString,
            )
        mockMvc.delete("$tests/$second") { with(alice) }.andExpect { status { isNoContent() } }
        mockMvc.get(tests) { with(alice) }.andExpect { jsonPath("$.length()") { value(1) } }

        mockMvc.delete("/snippets/$snippet") { with(alice) }.andExpect { status { isNoContent() } }
        assertTrue(repository.findBySnippetIdOrderByCreatedAtAscIdAsc(snippet).isEmpty())
    }
}
