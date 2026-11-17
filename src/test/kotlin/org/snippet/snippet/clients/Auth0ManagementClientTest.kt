package org.snippet.snippet.clients

import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.auth0.Auth0ManagementClient
import org.snippet.snippet.clients.auth0.Auth0ManagementProperties
import org.snippet.snippet.clients.auth0.Auth0Query
import org.snippet.snippet.clients.auth0.UserSearchResult
import org.snippet.snippet.clients.auth0.UserSummary
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.http.OutboundHeadersInterceptor
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Auth0ManagementClientTest {
    private val clock = MutableClock(Instant.parse("2026-11-17T10:00:00Z"))

    // With the interceptor every Spring builder gets, to check the user's token doesn't reach Auth0
    private val builder = RestClient.builder().requestInterceptor(OutboundHeadersInterceptor())
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val properties = Auth0ManagementProperties("tenant.test", "m2m-id", "m2m-secret")
    private val client = Auth0ManagementClient(builder, properties, clock)

    private val usersJson =
        """
        {"start":0,"limit":10,"length":3,"total":23,"users":[
          {"user_id":"auth0|bob","name":"Bob","nickname":"bobby","email":"bob@x.com"},
          {"user_id":"auth0|carol","nickname":"carol","email":"carol@x.com"},
          {"user_id":"google-oauth2|dave","name":"","email":"dave@x.com"}
        ]}
        """.trimIndent()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun expectToken(
        token: String = "m2m-token",
        expiresIn: Long = 86400,
    ) {
        server
            .expect(requestTo("https://tenant.test/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andExpect(
                content().formData(
                    LinkedMultiValueMap(
                        mapOf(
                            "grant_type" to listOf("client_credentials"),
                            "client_id" to listOf("m2m-id"),
                            "client_secret" to listOf("m2m-secret"),
                            "audience" to listOf("https://tenant.test/api/v2/"),
                        ),
                    ),
                ),
            ).andRespond(
                withSuccess(
                    """{"access_token":"$token","expires_in":$expiresIn,"token_type":"Bearer"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
    }

    private fun expectSearch(token: String = "m2m-token") {
        server
            .expect(requestTo(startsWith("https://tenant.test/api/v2/users?")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andRespond(withSuccess(usersJson, MediaType.APPLICATION_JSON))
    }

    private fun decodedQueryParam(
        name: String,
        expected: String,
    ) = RequestMatcher { request ->
        val raw =
            UriComponentsBuilder
                .fromUri(request.uri)
                .build()
                .queryParams
                .getFirst(name)
        assertEquals(expected, raw?.let { URLDecoder.decode(it, Charsets.UTF_8) })
    }

    private fun search(name: String? = "bo") = client.search(name, "auth0|alice", 0, 10)

    @Test
    fun `searches users with an m2m token and maps them, leaving the caller out`() {
        expectToken()
        server
            .expect(requestTo(startsWith("https://tenant.test/api/v2/users?")))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer m2m-token"))
            .andExpect(
                decodedQueryParam("q", """(name:bo* OR nickname:bo* OR email:bo*) AND NOT user_id:"auth0|alice""""),
            ).andExpect(queryParam("search_engine", "v3"))
            .andExpect(queryParam("page", "2"))
            .andExpect(queryParam("per_page", "5"))
            .andExpect(queryParam("include_totals", "true"))
            .andRespond(withSuccess(usersJson, MediaType.APPLICATION_JSON))

        val result = client.search("bo", "auth0|alice", 2, 5)

        assertEquals(
            UserSearchResult(
                23,
                listOf(
                    UserSummary("auth0|bob", "Bob"),
                    UserSummary("auth0|carol", "carol"),
                    UserSummary("google-oauth2|dave", "dave@x.com"),
                ),
            ),
            result,
        )
        server.verify()
    }

    @Test
    fun `a blank name searches every user but the caller`() {
        expectToken()
        server
            .expect(decodedQueryParam("q", """NOT user_id:"auth0|alice""""))
            .andRespond(withSuccess("""{"total":0,"users":[]}""", MediaType.APPLICATION_JSON))

        assertEquals(UserSearchResult(0, emptyList()), search("  "))
        server.verify()
    }

    @Test
    fun `drops the caller if auth0 returns it anyway`() {
        expectToken()
        server
            .expect(requestTo(startsWith("https://tenant.test/api/v2/users?")))
            .andRespond(
                withSuccess(
                    """{"total":2,"users":[{"user_id":"auth0|alice","name":"Alice"},{"user_id":"auth0|bob","name":"Bob"}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(listOf(UserSummary("auth0|bob", "Bob")), search().users)
    }

    @Test
    fun `reuses the token until shortly before it expires`() {
        expectToken("first", expiresIn = 3600)
        expectSearch("first")
        expectSearch("first")
        expectToken("second", expiresIn = 3600)
        expectSearch("second")

        search()
        clock.advance(Duration.ofMinutes(58))
        search()
        // Less than a minute left: renewed before using it
        clock.advance(Duration.ofSeconds(61))
        search()

        server.verify()
    }

    @Test
    fun `a very short lived token is renewed at half its life`() {
        expectToken("first", expiresIn = 60)
        expectSearch("first")
        expectSearch("first")
        expectToken("second", expiresIn = 60)
        expectSearch("second")

        search()
        clock.advance(Duration.ofSeconds(29))
        search()
        clock.advance(Duration.ofSeconds(1))
        search()

        server.verify()
    }

    @Test
    fun `never sends the user's token to auth0`() {
        val userJwt =
            Jwt
                .withTokenValue("user-token")
                .header("alg", "none")
                .subject("auth0|alice")
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(userJwt)
        // expectToken checks the token request has no Authorization header at all
        expectToken()
        expectSearch()

        search()

        server.verify()
    }

    @Test
    fun `a 401 from auth0 drops the token so the next call gets a new one`() {
        expectToken("revoked")
        server.expect(requestTo(startsWith("https://tenant.test/api/v2/users?"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        expectToken("fresh")
        expectSearch("fresh")

        assertThrows<UpstreamException> { search() }
        search()

        server.verify()
    }

    @Test
    fun `a rate limit or a failing token request is an upstream error`() {
        expectToken()
        server.expect(requestTo(startsWith("https://tenant.test/api/v2/users?"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        assertThrows<UpstreamException> { search() }

        server.reset()
        val other = Auth0ManagementClient(builder, properties, clock)
        server.expect(requestTo("https://tenant.test/oauth/token")).andRespond(withStatus(HttpStatus.FORBIDDEN))
        assertThrows<UpstreamException> { other.search("bo", "auth0|alice", 0, 10) }
    }

    @Test
    fun `without credentials it fails without calling auth0`() {
        val unconfigured = Auth0ManagementClient(builder, Auth0ManagementProperties(), clock)

        val error = assertThrows<UpstreamException> { unconfigured.search("bo", "auth0|alice", 0, 10) }
        assertThrows<UpstreamException> { unconfigured.exists("auth0|bob") }

        assertEquals(Auth0ManagementClient.SERVICE, error.service)
        assertTrue("AUTH0_DOMAIN" in error.message.orEmpty())
        server.verify()
    }

    @Test
    fun `checks whether a user exists, encoding the id`() {
        expectToken()
        server
            .expect(requestTo(startsWith("https://tenant.test/api/v2/users/auth0%7Cbob?")))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer m2m-token"))
            .andRespond(withSuccess("""{"user_id":"auth0|bob"}""", MediaType.APPLICATION_JSON))
        server
            .expect(
                requestTo(startsWith("https://tenant.test/api/v2/users/auth0%7Cnobody?")),
            ).andRespond(withStatus(HttpStatus.NOT_FOUND))
        server
            .expect(
                requestTo(startsWith("https://tenant.test/api/v2/users/auth0%7Ccarol?")),
            ).andRespond(withStatus(HttpStatus.BAD_GATEWAY))

        assertTrue(client.exists("auth0|bob"))
        assertFalse(client.exists("auth0|nobody"))
        assertThrows<UpstreamException> { client.exists("auth0|carol") }
        server.verify()
    }

    @Test
    fun `accepts the domain with a scheme or a trailing slash`() {
        assertEquals("https://tenant.test", Auth0ManagementProperties("https://tenant.test/", "a", "b").baseUrl)
        assertEquals("https://tenant.test", Auth0ManagementProperties(" tenant.test ", "a", "b").baseUrl)
        assertFalse(Auth0ManagementProperties("tenant.test", "a", "").configured)
    }

    @Test
    fun `escapes what the user types`() {
        assertEquals(
            """a\+b\-c\&\&d\|\|e\!\(f\)\{g\}\[h\]\^i\"j\~k\*l\?m\:n\\o\/p""",
            Auth0Query.escape("""a+b-c&&d||e!(f){g}[h]^i"j~k*l?m:n\o/p"""),
        )
        assertEquals(
            """(name:john\ sm* OR nickname:john\ sm* OR email:john\ sm*) AND NOT user_id:"x"""",
            Auth0Query.users(" john sm ", "x"),
        )
        assertEquals(
            """(name:a\:b\ OR\ c* OR nickname:a\:b\ OR\ c* OR email:a\:b\ OR\ c*) AND NOT user_id:"x"""",
            Auth0Query.users("a:b OR c", "x"),
        )
        assertEquals("""NOT user_id:"we\"ird\\id"""", Auth0Query.users(null, """we"ird\id"""))
    }
}

private class MutableClock(
    private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
