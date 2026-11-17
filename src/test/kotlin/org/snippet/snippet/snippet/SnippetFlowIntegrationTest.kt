package org.snippet.snippet.snippet

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.snippet.snippet.clients.auth0.UserDirectory
import org.snippet.snippet.clients.auth0.UserSummary
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.support.FakeAssets
import org.snippet.snippet.support.FakePermissions
import org.snippet.snippet.support.FakeRunner
import org.snippet.snippet.support.FakeUsers
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole CRUD through HTTP, with Postgres and in-memory versions of the other services. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Needs Docker for Postgres: skipped where there is none, runs in CI.
@Testcontainers(disabledWithoutDocker = true)
class SnippetFlowIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val objectMapper: ObjectMapper,
    @param:Autowired private val permissions: FakePermissions,
    @param:Autowired private val assets: FakeAssets,
    @param:Autowired private val repository: SnippetRepository,
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

        @Bean
        @Primary
        fun fakeUsers(): UserDirectory = FakeUsers(UserSummary("auth0|alice", "alice"), UserSummary("auth0|bob", "Bob"))
    }

    private val alice = jwt().jwt { it.subject("auth0|alice").claim("nickname", "alice") }
    private val bob = jwt().jwt { it.subject("auth0|bob") }

    private fun create(name: String): UUID {
        val body =
            mockMvc
                .post("/snippets") {
                    with(alice)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"$name","language":"printscript","version":"1.1","extension":"ps","content":"println(1);"}"""
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString
        return UUID.fromString(objectMapper.readTree(body)["id"].asText())
    }

    @Test
    fun `create, share, list, update, download and delete`() {
        val hello = create("Hello")
        val other = create("other")
        mockMvc
            .post("/snippets/$hello/share") {
                with(alice)
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":"auth0|bob"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(hello.toString()) }
            }
        assertEquals(Role.READ, permissions.roles[hello to "auth0|bob"])
        mockMvc.get("/users") { with(alice) }.andExpect {
            jsonPath("$.count") { value(1) }
            jsonPath("$.users[0].id") { value("auth0|bob") }
        }

        mockMvc
            .get("/snippets") {
                with(alice)
                param("name", "hel")
                param("compliance", "pending")
            }.andExpect {
                status { isOk() }
                jsonPath("$.count") { value(1) }
                jsonPath("$.snippets[0].id") { value(hello.toString()) }
                jsonPath("$.snippets[0].author") { value("alice") }
                jsonPath("$.snippets[0].relation") { value("owned") }
            }
        mockMvc.get("/snippets") { with(bob) }.andExpect {
            jsonPath("$.count") { value(1) }
            jsonPath("$.snippets[0].relation") { value("shared") }
        }
        mockMvc.get("/snippets/$other") { with(bob) }.andExpect { status { isNotFound() } }

        mockMvc
            .put("/snippets/$hello") {
                with(alice)
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"println(2);"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.content") { value("println(2);") }
                jsonPath("$.name") { value("Hello") }
            }
        mockMvc
            .put("/snippets/$hello") {
                with(alice)
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"!!"}"""
            }.andExpect { status { isUnprocessableEntity() } }

        mockMvc.get("/snippets/$hello/download") { with(bob) }.andExpect {
            status { isOk() }
            content { string("println(2);") }
            header { string("Content-Disposition", "attachment; filename=\"Hello.ps\"") }
        }

        mockMvc.delete("/snippets/$hello") { with(bob) }.andExpect { status { isForbidden() } }
        mockMvc.delete("/snippets/$hello") { with(alice) }.andExpect { status { isNoContent() } }
        mockMvc.get("/snippets/$hello") { with(alice) }.andExpect { status { isNotFound() } }

        assertEquals(listOf(other), repository.findAll().map { it.id })
        assertTrue(assets.stored.keys.none { it.second == hello.toString() })
    }
}
