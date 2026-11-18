package org.snippet.snippet.user

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verifyNoInteractions
import org.snippet.snippet.clients.auth0.UserDirectory
import org.snippet.snippet.clients.auth0.UserSearchResult
import org.snippet.snippet.clients.auth0.UserSummary
import org.snippet.snippet.common.error.UpstreamException
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

@WebMvcTest(controllers = [UserController::class])
@Import(SecurityConfig::class)
@ActiveProfiles("test")
class UserControllerTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var users: UserDirectory

    private val aliceToken = jwt().jwt { it.subject("auth0|alice") }

    @Test
    fun `needs a token`() {
        mockMvc.get("/users").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `searches with the defaults of the contract, leaving the caller out`() {
        given(users.search(null, "auth0|alice", 0, 10))
            .willReturn(UserSearchResult(1, listOf(UserSummary("auth0|bob", "Bob"))))

        mockMvc.get("/users") { with(aliceToken) }.andExpect {
            status { isOk() }
            jsonPath("$.page") { value(0) }
            jsonPath("$.page_size") { value(10) }
            jsonPath("$.count") { value(1) }
            jsonPath("$.users[0].id") { value("auth0|bob") }
            jsonPath("$.users[0].name") { value("Bob") }
        }
    }

    @Test
    fun `passes the name and paging through`() {
        given(users.search("bo", "auth0|alice", 2, 5)).willReturn(UserSearchResult(11, emptyList()))

        mockMvc
            .get("/users") {
                with(aliceToken)
                param("name", "bo")
                param("page", "2")
                param("page_size", "5")
            }.andExpect {
                status { isOk() }
                jsonPath("$.page") { value(2) }
                jsonPath("$.page_size") { value(5) }
                jsonPath("$.count") { value(11) }
                jsonPath("$.users") { isEmpty() }
            }
    }

    @Test
    fun `rejects bad paging with 400`() {
        listOf("page" to "-1", "page_size" to "0", "page_size" to "101").forEach { (name, value) ->
            mockMvc
                .get("/users") {
                    with(aliceToken)
                    param(name, value)
                }.andExpect {
                    status { isBadRequest() }
                    content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                }
        }
        verifyNoInteractions(users)
    }

    @Test
    fun `a failing auth0 is 502`() {
        given(users.search(null, "auth0|alice", 0, 10)).willThrow(UpstreamException("Auth0 Management API", "not configured"))

        mockMvc.get("/users") { with(aliceToken) }.andExpect {
            status { isBadGateway() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Auth0 Management API is not available") }
        }
    }
}
