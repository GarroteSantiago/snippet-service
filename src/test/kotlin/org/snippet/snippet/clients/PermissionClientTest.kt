package org.snippet.snippet.clients

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.permission.Action
import org.snippet.snippet.clients.permission.PermissionCheck
import org.snippet.snippet.clients.permission.RelationFilter
import org.snippet.snippet.clients.permission.RestPermissionClient
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.clients.permission.SnippetAccess
import org.snippet.snippet.common.error.UpstreamException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = RestPermissionClient(builder, "http://permissions")
    private val snippetId = UUID.fromString("6f1c2b8e-3d4a-4f7e-9a1b-2c3d4e5f6a7b")

    @Test
    fun `grants a role`() {
        server
            .expect(requestTo("http://permissions/permissions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"snippetId":"$snippetId","userId":"auth0|alice","role":"OWNER"}""", JsonCompareMode.STRICT))
            .andRespond(withStatus(HttpStatus.CREATED))

        assertTrue(client.grant(snippetId, "auth0|alice", Role.OWNER))

        server.verify()
    }

    @Test
    fun `a conflict on grant means the user already had a permission`() {
        server.expect(requestTo("http://permissions/permissions")).andRespond(withStatus(HttpStatus.CONFLICT))

        assertFalse(client.grant(snippetId, "auth0|bob", Role.READ))
    }

    @Test
    fun `any other error on grant is an upstream error`() {
        server.expect(requestTo("http://permissions/permissions")).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertThrows<UpstreamException> { client.grant(snippetId, "auth0|alice", Role.OWNER) }
    }

    @Test
    fun `checks an action, encoding the user id`() {
        server
            .expect(method(HttpMethod.GET))
            .andExpect(queryParam("snippetId", snippetId.toString()))
            .andExpect(queryParam("userId", "auth0%7Calice"))
            .andExpect(queryParam("action", "WRITE"))
            .andRespond(withSuccess("""{"allowed":false,"role":"READ"}""", MediaType.APPLICATION_JSON))

        assertEquals(PermissionCheck(false, Role.READ), client.check(snippetId, "auth0|alice", Action.WRITE))
    }

    @Test
    fun `a check without any role has a null role`() {
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("""{"allowed":false,"role":null}""", MediaType.APPLICATION_JSON))

        assertEquals(PermissionCheck(false, null), client.check(snippetId, "auth0|alice", Action.READ))
    }

    @Test
    fun `lists the accessible snippets for a relation`() {
        server
            .expect(queryParam("relation", "shared"))
            .andRespond(
                withSuccess("""{"snippets":[{"snippetId":"$snippetId","role":"READ"}]}""", MediaType.APPLICATION_JSON),
            )

        assertEquals(listOf(SnippetAccess(snippetId, Role.READ)), client.accessibleSnippets("auth0|alice", RelationFilter.SHARED))
    }

    @Test
    fun `revokes every permission of a snippet`() {
        server
            .expect(requestTo("http://permissions/permissions/snippets/$snippetId"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withNoContent())

        client.revokeAll(snippetId)

        server.verify()
    }
}
