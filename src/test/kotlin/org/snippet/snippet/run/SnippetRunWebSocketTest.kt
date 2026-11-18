package org.snippet.snippet.run

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetRepository
import org.snippet.snippet.snippet.SnippetService
import org.snippet.snippet.support.FakeAssets
import org.snippet.snippet.support.FakePermissions
import org.snippet.snippet.support.FakeRunner
import org.snippet.snippet.support.InMemorySnippetRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.ServerSocket
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The real endpoint over real sockets, without a database: the repository and the HTTP services
 * are in-memory fakes, and a fake Runner `/ws/run` lives in the same server (behind the same
 * security chain, so the relay has to send the user's token to reach it).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "snippet-run.start-timeout=500ms",
    ],
)
@ActiveProfiles("test")
class SnippetRunWebSocketTest(
    @param:Autowired private val json: ObjectMapper,
    @param:Autowired private val repository: SnippetRepository,
    @param:Autowired private val permissions: FakePermissions,
    @param:Autowired private val assets: FakeAssets,
    @param:Autowired private val fakeRunner: FakeRunnerEndpoint,
) {
    companion object {
        private val port = ServerSocket(0).use { it.localPort }
        const val ALICE = "auth0|alice"
        const val BOB = "auth0|bob"

        @JvmStatic
        @DynamicPropertySource
        fun ports(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { port }
            registry.add("services.runner.url") { "http://localhost:$port/fake-runner" }
        }
    }

    @TestConfiguration
    class Fakes {
        @Bean
        @Primary
        fun snippetRepository(): SnippetRepository = InMemorySnippetRepository()

        @Bean
        @Primary
        fun fakeRunnerClient(): RunnerClient = FakeRunner()

        @Bean
        @Primary
        fun fakePermissions() = FakePermissions()

        @Bean
        @Primary
        fun fakeAssets() = FakeAssets()

        // "<user>-token" is a valid token of auth0|<user>
        @Bean
        fun jwtDecoder() =
            JwtDecoder { token ->
                val user = token.removeSuffix("-token").takeIf { token.endsWith("-token") } ?: throw BadJwtException("bad token")
                Jwt
                    .withTokenValue(token)
                    .header("alg", "none")
                    .subject("auth0|$user")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build()
            }

        @Bean
        fun fakeRunnerEndpoint() = FakeRunnerEndpoint()

        @Bean
        fun fakeRunnerRoute(endpoint: FakeRunnerEndpoint) =
            WebSocketConfigurer { registry: WebSocketHandlerRegistry -> registry.addHandler(endpoint, "/fake-runner/ws/run") }
    }

    /**
     * Plays the Runner: answers the `start` with an output and an input-request, echoes the input
     * and ends with `done ok`; `stop` ends with `done stopped`.
     */
    class FakeRunnerEndpoint : TextWebSocketHandler() {
        val starts = LinkedBlockingQueue<Map<*, *>>()
        val authorizations = LinkedBlockingQueue<String>()
        val closed = LinkedBlockingQueue<CloseStatus>()
        private val json = ObjectMapper()

        override fun afterConnectionEstablished(session: WebSocketSession) {
            authorizations.put(session.handshakeHeaders.getFirst(HttpHeaders.AUTHORIZATION).orEmpty())
        }

        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
            val incoming = json.readValue(message.payload, Map::class.java)
            when (incoming["type"]) {
                "start" -> {
                    starts.put(incoming)
                    session.send("type" to "output", "value" to "started")
                    session.send("type" to "input-request", "prompt" to "Name?")
                }

                "input" -> {
                    session.send("type" to "output", "value" to "Hello ${incoming["value"]}")
                    session.send("type" to "done", "status" to "ok")
                    session.close(CloseStatus.NORMAL)
                }

                "stop" -> {
                    session.send("type" to "done", "status" to "stopped")
                    session.close(CloseStatus.NORMAL)
                }
            }
        }

        override fun afterConnectionClosed(
            session: WebSocketSession,
            status: CloseStatus,
        ) {
            closed.put(status)
        }

        private fun WebSocketSession.send(vararg fields: Pair<String, String>) =
            sendMessage(TextMessage(json.writeValueAsString(mapOf(*fields))))
    }

    private val sessions = mutableListOf<WebSocketSession>()

    @AfterEach
    fun cleanUp() {
        sessions.forEach { runCatching { it.close() } }
        fakeRunner.starts.clear()
        fakeRunner.authorizations.clear()
        fakeRunner.closed.clear()
    }

    /** A browser-like client: no headers, everything in the messages. */
    private inner class Client(
        path: String,
    ) : TextWebSocketHandler() {
        val messages = LinkedBlockingQueue<Map<*, *>>()
        val closed = LinkedBlockingQueue<CloseStatus>()
        val session: WebSocketSession =
            StandardWebSocketClient()
                .execute(this, WebSocketHttpHeaders(), URI("ws://localhost:$port$path"))
                .get(5, TimeUnit.SECONDS)
                .also { sessions += it }

        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
            messages.put(json.readValue(message.payload, Map::class.java))
        }

        override fun afterConnectionClosed(
            session: WebSocketSession,
            status: CloseStatus,
        ) {
            closed.put(status)
        }

        fun send(vararg fields: Pair<String, String>) = session.sendMessage(TextMessage(json.writeValueAsString(mapOf(*fields))))

        fun next(): Map<*, *> = messages.poll(5, TimeUnit.SECONDS) ?: error("no message in 5 s")

        fun closeCode(): Int = (closed.poll(5, TimeUnit.SECONDS) ?: error("not closed in 5 s")).code
    }

    private fun snippetOf(owner: String): UUID {
        val id = UUID.randomUUID()
        repository.save(Snippet(id, "hello", null, "printscript", "1.1", "ps", owner, owner))
        assets.stored[SnippetService.CONTENT to id.toString()] = "println(\"hi\");"
        permissions.roles[id to owner] = Role.OWNER
        return id
    }

    private fun run(id: UUID) = Client("/ws/snippets/$id/run")

    @Test
    fun `relays a whole run both ways with the user's token`() {
        val id = snippetOf(ALICE)
        permissions.roles[id to BOB] = Role.READ
        val client = run(id)

        client.send("type" to "start", "token" to "bob-token")

        assertEquals(mapOf("type" to "output", "value" to "started"), client.next())
        assertEquals(mapOf("type" to "input-request", "prompt" to "Name?"), client.next())
        client.send("type" to "input", "value" to "Ada")
        assertEquals(mapOf("type" to "output", "value" to "Hello Ada"), client.next())
        assertEquals(mapOf("type" to "done", "status" to "ok"), client.next())
        assertEquals(CloseStatus.NORMAL.code, client.closeCode())

        assertEquals("Bearer bob-token", fakeRunner.authorizations.poll(1, TimeUnit.SECONDS))
        val start = fakeRunner.starts.poll(1, TimeUnit.SECONDS)!!
        assertEquals("start", start["type"])
        assertEquals("println(\"hi\");", start["content"])
        assertEquals("1.1", start["version"])
        assertEquals(true, (start["correlationId"] as String).isNotBlank())
        assertEquals("bob-token", permissions.lastCheckToken)
        assertEquals("bob-token", assets.lastGetToken)
    }

    @Test
    fun `stop is relayed to the runner`() {
        val client = run(snippetOf(ALICE))
        client.send("type" to "start", "token" to "alice-token")
        client.next()
        client.next()

        client.send("type" to "stop")

        assertEquals(mapOf("type" to "done", "status" to "stopped"), client.next())
        assertEquals(CloseStatus.NORMAL.code, client.closeCode())
    }

    @Test
    fun `closing the client closes the runner socket`() {
        val client = run(snippetOf(ALICE))
        client.send("type" to "start", "token" to "alice-token")
        client.next()
        assertEquals("input-request", client.next()["type"])

        client.session.close()

        assertEquals(CloseStatus.NORMAL.code, fakeRunner.closed.poll(5, TimeUnit.SECONDS)?.code)
    }

    @Test
    fun `without a start in time it closes with 4401`() {
        assertEquals(4401, run(snippetOf(ALICE)).closeCode())
    }

    @Test
    fun `an invalid token or another first message closes with 4401`() {
        val id = snippetOf(ALICE)

        run(id).apply { send("type" to "start", "token" to "forged") }.let { assertEquals(4401, it.closeCode()) }
        run(id).apply { send("type" to "input", "value" to "1") }.let { assertEquals(4401, it.closeCode()) }
        run(id).apply { send("type" to "start") }.let { assertEquals(4401, it.closeCode()) }
        assertNull(fakeRunner.starts.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `no access, a missing snippet or a bad id close with 4404`() {
        val id = snippetOf(ALICE)

        run(id).apply { send("type" to "start", "token" to "bob-token") }.let { assertEquals(4404, it.closeCode()) }
        run(UUID.randomUUID()).apply { send("type" to "start", "token" to "alice-token") }.let { assertEquals(4404, it.closeCode()) }
        Client("/ws/snippets/not-an-id/run").apply { send("type" to "start", "token" to "alice-token") }.let {
            assertEquals(4404, it.closeCode())
        }
        assertNull(fakeRunner.starts.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a second start closes with 4409`() {
        val client = run(snippetOf(ALICE))
        client.send("type" to "start", "token" to "alice-token")
        client.next()

        client.send("type" to "start", "token" to "alice-token")

        assertEquals(4409, client.closeCode())
        assertEquals(1, fakeRunner.starts.size)
    }
}
