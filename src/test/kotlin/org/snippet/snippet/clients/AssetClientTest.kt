package org.snippet.snippet.clients

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.asset.RestAssetClient
import org.snippet.snippet.common.error.UpstreamException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = RestAssetClient(builder, "http://assets")

    @Test
    fun `puts the content as UTF-8 bytes`() {
        server
            .expect(requestTo("http://assets/v1/asset/snippets/abc"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().bytes("println(\"ñ\");".toByteArray(Charsets.UTF_8)))
            .andRespond(withStatus(HttpStatus.CREATED))

        client.put("snippets", "abc", "println(\"ñ\");")

        server.verify()
    }

    @Test
    fun `overwriting answers 200 and is fine too`() {
        server.expect(requestTo("http://assets/v1/asset/snippets/abc")).andRespond(withSuccess())

        client.put("snippets", "abc", "x")

        server.verify()
    }

    @Test
    fun `gets the content`() {
        server
            .expect(requestTo("http://assets/v1/asset/snippets/abc"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("println(\"ñ\");".toByteArray(Charsets.UTF_8), MediaType.APPLICATION_OCTET_STREAM))

        assertEquals("println(\"ñ\");", client.get("snippets", "abc"))
    }

    @Test
    fun `gets with the token it is given`() {
        server
            .expect(requestTo("http://assets/v1/asset/snippets/abc"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
            .andRespond(withSuccess("x", MediaType.APPLICATION_OCTET_STREAM))

        assertEquals("x", client.get("snippets", "abc", token = "user-token"))
    }

    @Test
    fun `an empty asset is an empty string`() {
        server.expect(requestTo("http://assets/v1/asset/snippets/abc")).andRespond(withSuccess())

        assertEquals("", client.get("snippets", "abc"))
    }

    @Test
    fun `a missing asset is null`() {
        server.expect(requestTo("http://assets/v1/asset/snippets/abc")).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertNull(client.get("snippets", "abc"))
    }

    @Test
    fun `deleting a missing asset is not an error`() {
        server
            .expect(requestTo("http://assets/v1/asset/snippets/abc"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))
        server.expect(requestTo("http://assets/v1/asset/snippets/def")).andRespond(withNoContent())

        client.delete("snippets", "abc")
        client.delete("snippets", "def")

        server.verify()
    }

    @Test
    fun `server errors are upstream errors`() {
        server.expect(requestTo("http://assets/v1/asset/snippets/abc")).andRespond(withServerError())

        assertThrows<UpstreamException> { client.get("snippets", "abc") }
    }
}
