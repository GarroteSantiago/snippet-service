package org.snippet.snippet.common.http

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.support.MutableClock
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class ClientCredentialsTokenTest {
    private val clock = MutableClock(Instant.parse("2026-11-18T10:00:00Z"))
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()

    private fun tokens(client: RestClient = builder.build()) =
        ClientCredentialsToken(
            client = client,
            service = "Token endpoint",
            tokenUri = "https://issuer.test/oauth/token",
            clientId = "service-id",
            clientSecret = "service-secret",
            audience = "https://api.test",
            clock = clock,
        )

    private fun expectToken(
        token: String,
        expiresIn: Long = 3600,
    ) {
        server
            .expect(requestTo("https://issuer.test/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().formData(
                    LinkedMultiValueMap(
                        mapOf(
                            "grant_type" to listOf("client_credentials"),
                            "client_id" to listOf("service-id"),
                            "client_secret" to listOf("service-secret"),
                            "audience" to listOf("https://api.test"),
                        ),
                    ),
                ),
            ).andRespond(
                withSuccess("""{"access_token":"$token","token_type":"Bearer","expires_in":$expiresIn}""", MediaType.APPLICATION_JSON),
            )
    }

    @Test
    fun `asks for a token once and reuses it until a minute before it expires`() {
        expectToken("first")
        expectToken("second")
        val tokens = tokens()

        assertEquals("first", tokens.get())
        clock.advance(Duration.ofMinutes(58))
        assertEquals("first", tokens.get())
        clock.advance(Duration.ofSeconds(61))
        assertEquals("second", tokens.get())

        server.verify()
    }

    @Test
    fun `a short lived token is renewed at half its life`() {
        expectToken("first", expiresIn = 60)
        expectToken("second", expiresIn = 60)
        val tokens = tokens()

        assertEquals("first", tokens.get())
        clock.advance(Duration.ofSeconds(29))
        assertEquals("first", tokens.get())
        clock.advance(Duration.ofSeconds(1))
        assertEquals("second", tokens.get())

        server.verify()
    }

    @Test
    fun `invalidating forgets the token`() {
        expectToken("first")
        expectToken("second")
        val tokens = tokens()

        assertEquals("first", tokens.get())
        tokens.invalidate()
        assertEquals("second", tokens.get())

        server.verify()
    }

    @Test
    fun `a failing token request is an upstream error and the next get tries again`() {
        server.expect(requestTo("https://issuer.test/oauth/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        server.expect(requestTo("https://issuer.test/oauth/token")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        expectToken("good")
        val tokens = tokens()

        val error = assertThrows<UpstreamException> { tokens.get() }
        assertEquals("Token endpoint", error.service)
        // A body without access_token is not a token either
        assertThrows<UpstreamException> { tokens.get() }
        assertEquals("good", tokens.get())

        server.verify()
    }

    @Test
    fun `threads asking at the same time share one token request`() {
        val requests = AtomicInteger()
        val factory =
            ClientHttpRequestFactory { uri, method ->
                requests.incrementAndGet()
                // Slow enough for the other threads to be waiting on the lock
                Thread.sleep(50)
                MockClientHttpRequest(method, uri).apply {
                    setResponse(
                        MockClientHttpResponse("""{"access_token":"shared","expires_in":3600}""".toByteArray(), HttpStatus.OK).apply {
                            headers.contentType = MediaType.APPLICATION_JSON
                        },
                    )
                }
            }
        val tokens = tokens(RestClient.builder().requestFactory(factory).build())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)

        val results =
            List(8) {
                pool.submit<String> {
                    start.await()
                    tokens.get()
                }
            }
        start.countDown()

        assertEquals(List(8) { "shared" }, results.map { it.get(5, TimeUnit.SECONDS) })
        assertEquals(1, requests.get())
        pool.shutdown()
    }

    @Test
    fun `the client without the forwarded token sends no user token`() {
        val withInterceptor = RestClient.builder().requestInterceptor(OutboundHeadersInterceptor())
        val mock = MockRestServiceServer.bindTo(withInterceptor).build()
        val tokens = tokens(ClientCredentialsToken.withoutForwardedToken(withInterceptor))
        mock
            .expect(requestTo("https://issuer.test/oauth/token"))
            .andExpect { assertEquals(null, it.headers.getFirst(HttpHeaders.AUTHORIZATION)) }
            .andRespond(withSuccess("""{"access_token":"t","expires_in":3600}""", MediaType.APPLICATION_JSON))

        expectToken("outer")

        // Even inside a background call (another token in flight), the token endpoint gets no bearer token
        assertEquals("t", BackgroundCalls(tokens()).call { tokens.get() })

        mock.verify()
        server.verify()
    }
}
