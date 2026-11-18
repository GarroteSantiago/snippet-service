package org.snippet.snippet.run

import jakarta.websocket.ContainerProvider
import org.snippet.snippet.common.logging.CorrelationIdFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

@ConfigurationProperties("snippet-run")
data class RunProperties(
    /** Time the client has to send its `start` with the token (`contracts/websocket.md`: 5 s). */
    val startTimeout: Duration = Duration.ofSeconds(5),
    /**
     * Origins allowed to open the socket. Any by default: the socket is authenticated by the token
     * in the first message, not by cookies, so another site can't use the user's session.
     */
    val allowedOrigins: List<String> = listOf("*"),
)

/** `/ws/snippets/{snippetId}/run` (public as `/api/ws/snippets/{snippetId}/run` through NGINX). */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(RunProperties::class)
class RunWebSocketConfig(
    private val handler: SnippetRunHandler,
    private val properties: RunProperties,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry
            .addHandler(handler, SnippetRunHandler.PATH_PATTERN)
            .setAllowedOriginPatterns(*properties.allowedOrigins.toTypedArray())
    }
}

/** Opens the socket to the Runner's `/ws/run` on behalf of a user. */
@Component
class RunnerSocketConnector(
    @Value("\${services.runner.url}") baseUrl: String,
) {
    private val uri = URI(baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/ws/run")

    private val client =
        StandardWebSocketClient(
            ContainerProvider.getWebSocketContainer().apply {
                // Tomcat's default is 8 KiB; a single println can be longer
                defaultMaxTextMessageBufferSize = MAX_MESSAGE_SIZE
            },
        )

    /** The Runner's handshake goes through its security chain: it gets the user's token, as any call. */
    fun connect(
        token: String,
        correlationId: String,
        handler: WebSocketHandler,
    ): WebSocketSession {
        val headers = WebSocketHttpHeaders()
        headers.setBearerAuth(token)
        headers.set(CorrelationIdFilter.HEADER, correlationId)
        return client.execute(handler, headers, uri).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        private const val MAX_MESSAGE_SIZE = 1024 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 5L
    }
}
