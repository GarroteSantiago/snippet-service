package org.snippet.snippet.common.http

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.upstream
import org.snippet.snippet.common.error.UpstreamException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Calls inside [BackgroundCalls.call] carry the service's token, as every Spring `RestClient` would send it. */
class BackgroundCallsTest {
    // With the interceptor every Spring builder gets
    private val builder = RestClient.builder().requestInterceptor(OutboundHeadersInterceptor())
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val runner = builder.build()
    private val properties = ServiceTokenProperties("https://issuer.test/oauth/token", "service-id", "service-secret", "https://api.test")
    private val background = BackgroundCalls(builder, properties)

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun expectToken(token: String) {
        server
            .expect(requestTo("https://issuer.test/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andRespond(withSuccess("""{"access_token":"$token","token_type":"Bearer","expires_in":86400}""", MediaType.APPLICATION_JSON))
    }

    private fun expectVersions(authorization: String?) {
        val expectation = server.expect(requestTo("http://runner.test/versions"))
        if (authorization == null) {
            expectation.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
        } else {
            expectation.andExpect(header(HttpHeaders.AUTHORIZATION, authorization))
        }
        expectation.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
    }

    private fun versions() =
        upstream("Runner") {
            runner
                .get()
                .uri("http://runner.test/versions")
                .retrieve()
                .body(String::class.java)
        }

    private fun signIn() {
        val jwt =
            Jwt
                .withTokenValue("user-token")
                .header("alg", "none")
                .subject("auth0|alice")
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `a call inside carries the service token, fetched once`() {
        expectToken("service-token")
        expectVersions("Bearer service-token")
        expectVersions("Bearer service-token")

        background.call { versions() }
        background.call { versions() }

        server.verify()
    }

    @Test
    fun `inside it the service token replaces the user's, which is back afterwards`() {
        signIn()
        expectToken("service-token")
        expectVersions("Bearer service-token")
        expectVersions("Bearer service-token")
        expectVersions("Bearer user-token")

        background.call {
            versions()
            // Nested: still the service's token after the inner call ends
            background.call { assertEquals("service-token", BackgroundCalls.currentToken()) }
            versions()
        }
        assertNull(BackgroundCalls.currentToken())
        versions()

        server.verify()
    }

    @Test
    fun `an explicit authorization header wins`() {
        expectToken("service-token")
        server
            .expect(requestTo("http://runner.test/versions"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer explicit"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        background.call {
            runner
                .get()
                .uri("http://runner.test/versions")
                .headers { it.setBearerAuth("explicit") }
                .retrieve()
                .toBodilessEntity()
        }

        server.verify()
    }

    @Test
    fun `a 401 drops the token so the next call gets a new one`() {
        expectToken("revoked")
        server.expect(requestTo("http://runner.test/versions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        expectToken("fresh")
        expectVersions("Bearer fresh")

        assertThrows<UpstreamException> { background.call { versions() } }
        assertNull(BackgroundCalls.currentToken())
        background.call { versions() }

        server.verify()
    }

    @Test
    fun `if the token can't be obtained the call fails without running`() {
        server.expect(requestTo("https://issuer.test/oauth/token")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        var ran = false

        val error = assertThrows<UpstreamException> { background.call { ran = true } }

        assertEquals(BackgroundCalls.SERVICE, error.service)
        assertFalse(ran)
        server.verify()
    }

    @Test
    fun `without credentials the calls go out without a token`() {
        val unconfigured = BackgroundCalls(builder, ServiceTokenProperties(tokenUri = "https://issuer.test/oauth/token"))
        expectVersions(authorization = null)

        unconfigured.call { versions() }

        server.verify()
    }

    @Test
    fun `credentials are complete only with every value`() {
        assertTrue(properties.configured)
        assertFalse(properties.copy(clientSecret = " ").configured)
        assertFalse(properties.copy(tokenUri = "").configured)
        assertFalse(properties.copy(audience = "").configured)
        assertFalse(ServiceTokenProperties().configured)
    }
}
