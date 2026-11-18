package org.snippet.snippet.snippet

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.snippet.snippet.clients.permission.RelationFilter
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.common.error.ForbiddenException
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.security.CurrentUser
import org.snippet.snippet.common.security.SecurityConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
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
import java.time.Instant
import java.util.UUID

@WebMvcTest(controllers = [SnippetController::class])
@Import(SecurityConfig::class)
@ActiveProfiles("test")
class SnippetControllerTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var service: SnippetService

    private val id = UUID.fromString("6f1c2b8e-3d4a-4f7e-9a1b-2c3d4e5f6a7b")
    private val alice = CurrentUser("auth0|alice", "Alice")
    private val aliceToken = jwt().jwt { it.subject("auth0|alice").claim("name", "Alice") }

    private val snippet =
        SnippetResponse(
            id = id,
            name = "hello",
            description = null,
            language = "printscript",
            version = "1.1",
            extension = "ps",
            content = "println(1);",
            compliance = Compliance.NOT_COMPLIANT,
            author = "Alice",
            authorId = "auth0|alice",
            relation = Relation.OWNED,
            lintViolations = listOf(Diagnostic("no-println-expr", "Use a variable", 1, 9)),
            createdAt = Instant.parse("2026-11-17T10:00:00Z"),
            updatedAt = Instant.parse("2026-11-17T10:00:00Z"),
        )

    @Test
    fun `needs a token`() {
        mockMvc.get("/snippets").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `creates a snippet as the caller, with its name from the token`() {
        val request = CreateSnippetRequest("hello", null, "printscript", "1.1", "ps", "println(1);")
        given(service.create(alice, request)).willReturn(snippet)

        mockMvc
            .post("/snippets") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"hello","language":"printscript","version":"1.1","extension":"ps","content":"println(1);"}"""
            }.andExpect {
                status { isCreated() }
                header { string(HttpHeaders.LOCATION, "http://localhost/snippets/$id") }
                jsonPath("$.id") { value(id.toString()) }
                jsonPath("$.compliance") { value("not-compliant") }
                jsonPath("$.relation") { value("owned") }
                jsonPath("$.author") { value("Alice") }
                jsonPath("$.authorId") { value("auth0|alice") }
                jsonPath("$.lintViolations[0].rule") { value("no-println-expr") }
                jsonPath("$.createdAt") { value("2026-11-17T10:00:00Z") }
                jsonPath("$.description") { doesNotExist() }
            }
    }

    @Test
    fun `without a name claim the author is the sub`() {
        val request = CreateSnippetRequest("hello", null, "printscript", "1.1", "ps", "println(1);")
        given(service.create(CurrentUser("auth0|bob", "auth0|bob"), request)).willReturn(snippet)

        mockMvc
            .post("/snippets") {
                with(jwt().jwt { it.subject("auth0|bob") })
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"hello","language":"printscript","version":"1.1","extension":"ps","content":"println(1);"}"""
            }.andExpect { status { isCreated() } }
    }

    @Test
    fun `rejects a malformed create with 400`() {
        mockMvc
            .post("/snippets") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"  ","language":"printscript","version":"1.1","extension":"ps","content":""}"""
            }.andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            }
        mockMvc
            .post("/snippets") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"hello"}"""
            }.andExpect { status { isBadRequest() } }
        verifyNoInteractions(service)
    }

    @Test
    fun `invalid code is 422 with diagnostics`() {
        val request = CreateSnippetRequest("hello", null, "printscript", "1.1", "ps", "let x")
        given(service.create(alice, request)).willThrow(InvalidCodeException(listOf(Diagnostic(null, "Expected ';'", 1, 6))))

        mockMvc
            .post("/snippets") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"hello","language":"printscript","version":"1.1","extension":"ps","content":"let x"}"""
            }.andExpect {
                status { isUnprocessableEntity() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(422) }
                jsonPath("$.diagnostics[0].message") { value("Expected ';'") }
                jsonPath("$.diagnostics[0].line") { value(1) }
                jsonPath("$.diagnostics[0].column") { value(6) }
            }
    }

    @Test
    fun `gets a snippet`() {
        given(service.get(alice, id)).willReturn(snippet)

        mockMvc.get("/snippets/$id") { with(aliceToken) }.andExpect {
            status { isOk() }
            jsonPath("$.content") { value("println(1);") }
        }
    }

    @Test
    fun `an id that isn't a uuid is 400`() {
        mockMvc.get("/snippets/not-a-uuid") { with(aliceToken) }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `not found and forbidden come out as problem details`() {
        given(service.get(alice, id)).willThrow(NotFoundException("Snippet $id not found"))
        given(service.update(alice, id, UpdateSnippetRequest(name = "x"))).willThrow(ForbiddenException())

        mockMvc.get("/snippets/$id") { with(aliceToken) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Snippet $id not found") }
        }
        mockMvc
            .put("/snippets/$id") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"x"}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `a failing upstream service is 502`() {
        given(service.get(alice, id)).willThrow(UpstreamException("Asset Service", "answered 500"))

        mockMvc.get("/snippets/$id") { with(aliceToken) }.andExpect {
            status { isBadGateway() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `updates only what comes`() {
        given(service.update(alice, id, UpdateSnippetRequest(content = "println(2);"))).willReturn(snippet)

        mockMvc
            .put("/snippets/$id") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"println(2);"}"""
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `rejects a blank name on update`() {
        mockMvc
            .put("/snippets/$id") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"   "}"""
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `deletes with 204`() {
        mockMvc.delete("/snippets/$id") { with(aliceToken) }.andExpect { status { isNoContent() } }

        verify(service).delete(alice, id)
    }

    @Test
    fun `lists with the defaults of the contract`() {
        given(
            service.list(alice, SnippetQuery()),
        ).willReturn(SnippetPage(0, 10, 1, listOf(snippet.copy(content = null, lintViolations = null))))

        mockMvc.get("/snippets") { with(aliceToken) }.andExpect {
            status { isOk() }
            jsonPath("$.page") { value(0) }
            jsonPath("$.page_size") { value(10) }
            jsonPath("$.count") { value(1) }
            jsonPath("$.snippets[0].name") { value("hello") }
            jsonPath("$.snippets[0].content") { doesNotExist() }
            jsonPath("$.snippets[0].lintViolations") { doesNotExist() }
        }
    }

    @Test
    fun `lists with filters, sort and paging`() {
        val query =
            SnippetQuery(
                page = 1,
                pageSize = 20,
                filter = SnippetFilter("hel", "printscript", Compliance.NOT_COMPLIANT),
                relation = RelationFilter.SHARED,
                sort = SortField.COMPLIANCE,
                order = SortOrder.DESC,
            )
        given(service.list(alice, query)).willReturn(SnippetPage(1, 20, 0, emptyList()))

        mockMvc
            .get("/snippets") {
                with(aliceToken)
                param("page", "1")
                param("page_size", "20")
                param("name", "hel")
                param("language", "printscript")
                param("compliance", "not-compliant")
                param("relation", "shared")
                param("sort", "compliance")
                param("order", "desc")
            }.andExpect {
                status { isOk() }
                jsonPath("$.page_size") { value(20) }
            }
    }

    @Test
    fun `rejects bad listing parameters with 400`() {
        listOf("page" to "-1", "page_size" to "0", "page_size" to "101", "compliance" to "great", "sort" to "author", "order" to "up")
            .forEach { (name, value) ->
                mockMvc
                    .get("/snippets") {
                        with(aliceToken)
                        param(name, value)
                    }.andExpect {
                        status { isBadRequest() }
                        content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                    }
            }
        verifyNoInteractions(service)
    }

    @Test
    fun `downloads as a text attachment named after the snippet`() {
        given(service.download(alice, id, false)).willReturn(SnippetFile("hello.ps", "println(1);"))

        mockMvc.get("/snippets/$id/download") { with(aliceToken) }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_PLAIN) }
            content { string("println(1);") }
            header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hello.ps\"") }
        }
    }

    @Test
    fun `downloads the formatted version, encoding a non ascii name`() {
        given(service.download(alice, id, true)).willReturn(SnippetFile("canción.ps", "println(1);\n"))

        mockMvc
            .get("/snippets/$id/download") {
                with(aliceToken)
                param("formatted", "true")
            }.andExpect {
                status { isOk() }
                header { string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename*=UTF-8''canci%C3%B3n.ps")) }
            }
    }

    @Test
    fun `shares a snippet and returns it`() {
        given(service.share(alice, id, "auth0|bob")).willReturn(snippet)

        mockMvc
            .post("/snippets/$id/share") {
                with(aliceToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":"auth0|bob"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(id.toString()) }
                jsonPath("$.relation") { value("owned") }
            }
    }

    @Test
    fun `share errors come out as problem details`() {
        given(service.share(alice, id, "auth0|bob")).willThrow(ForbiddenException())
        given(service.share(alice, id, "auth0|carol")).willThrow(NotFoundException("Snippet $id not found"))
        given(service.share(alice, id, "auth0|alice")).willThrow(BadRequestException("You can't share a snippet with yourself"))
        given(service.share(alice, id, "auth0|dave")).willThrow(UpstreamException("Auth0 Management API", "answered 429"))

        mapOf("auth0|bob" to 403, "auth0|carol" to 404, "auth0|alice" to 400, "auth0|dave" to 502).forEach { (userId, status) ->
            mockMvc
                .post("/snippets/$id/share") {
                    with(aliceToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"userId":"$userId"}"""
                }.andExpect {
                    status { isEqualTo(status) }
                    content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                }
        }
    }

    @Test
    fun `rejects a share without a user with 400`() {
        listOf("""{"userId":"  "}""", "{}").forEach { body ->
            mockMvc
                .post("/snippets/$id/share") {
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
}
