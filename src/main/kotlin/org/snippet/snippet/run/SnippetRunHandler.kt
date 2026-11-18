package org.snippet.snippet.run

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.snippet.snippet.common.error.ForbiddenException
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.logging.CorrelationIdFilter
import org.snippet.snippet.common.logging.UserMdcFilter
import org.snippet.snippet.common.security.CurrentUser
import org.snippet.snippet.snippet.SnippetService
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.util.UriTemplate
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Interactive run of a snippet (US-10, `contracts/websocket.md`). The browser can't send headers
 * on a WebSocket, so the handshake is public and the first message, `start`, carries the JWT. It is
 * validated with the same [JwtDecoder] as the HTTP API (signature, `iss`, `aud`, `exp`); then the
 * user needs `READ` on the snippet. After that a socket to the Runner is opened with the user's
 * token, the Runner gets its `start` with the code, and every message is relayed unchanged both
 * ways. Either side closing closes the other.
 *
 * Close codes: `4401` no valid `start` within the timeout, `4404` the snippet doesn't exist or the
 * user has no access at all (as HTTP 404: ids don't leak), `4403` access without `READ`, `4409` a
 * second `start` in the same session.
 */
@Component
class SnippetRunHandler(
    private val jwtDecoder: JwtDecoder,
    private val snippets: SnippetService,
    private val runner: RunnerSocketConnector,
    private val properties: RunProperties,
    private val json: ObjectMapper,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val timer =
        Executors.newSingleThreadScheduledExecutor(
            Thread
                .ofPlatform()
                .name("run-start-timeout")
                .daemon()
                .factory(),
        )
    private val runs = ConcurrentHashMap<String, Run>()

    private enum class State { AWAITING_START, RUNNING }

    /** One client socket and, once started, its socket to the Runner. */
    private inner class Run(
        val client: WebSocketSession,
        val correlationId: String,
    ) {
        @Volatile var state = State.AWAITING_START

        @Volatile var runner: WebSocketSession? = null
        var startTimeout: ScheduledFuture<*>? = null
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val correlationId =
            session.handshakeHeaders
                .getFirst(CorrelationIdFilter.HEADER)
                ?.takeIf { CorrelationIdFilter.isSafe(it) }
                ?: UUID.randomUUID().toString()
        // Sent to from the Runner's socket and from here; the decorator serializes the sends and
        // drops a client that doesn't read instead of buffering without bound
        val run = Run(ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT), correlationId)
        runs[session.id] = run
        run.startTimeout =
            timer.schedule(
                { if (run.state == State.AWAITING_START) close(run.client, UNAUTHENTICATED) },
                properties.startTimeout.toMillis(),
                TimeUnit.MILLISECONDS,
            )
    }

    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val run = runs[session.id] ?: return
        val type = runCatching { json.readTree(message.payload)?.get("type")?.asText() }.getOrNull()
        when (run.state) {
            State.AWAITING_START -> start(run, message.payload, type)
            State.RUNNING ->
                if (type == "start") {
                    close(run.client, ALREADY_RUNNING)
                } else {
                    send(run.runner, message)
                }
        }
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        val run = runs.remove(session.id) ?: return
        run.startTimeout?.cancel(false)
        run.runner?.let { close(it, CloseStatus.NORMAL) }
    }

    private fun start(
        run: Run,
        payload: String,
        type: String?,
    ) {
        val token =
            (if (type == "start") runCatching { json.readTree(payload).get("token")?.asText() }.getOrNull() else null)
                ?.takeIf { it.isNotBlank() }
                ?: return close(run.client, UNAUTHENTICATED)
        val user =
            try {
                CurrentUser.from(jwtDecoder.decode(token))
            } catch (_: JwtException) {
                return close(run.client, UNAUTHENTICATED)
            }
        run.state = State.RUNNING
        run.startTimeout?.cancel(false)

        val snippetId = snippetIdOf(run.client) ?: return close(run.client, NOT_FOUND)
        MDC.put(CorrelationIdFilter.MDC_KEY, run.correlationId)
        MDC.put(UserMdcFilter.MDC_KEY, user.id)
        try {
            val snippet =
                try {
                    snippets.runnable(user, snippetId, token)
                } catch (_: NotFoundException) {
                    return close(run.client, NOT_FOUND)
                } catch (_: ForbiddenException) {
                    return close(run.client, FORBIDDEN)
                }
            log.info("Interactive run of snippet {}", snippetId)
            val runnerSession = runner.connect(token, run.correlationId, RunnerRelay(run))
            run.runner = ConcurrentWebSocketSessionDecorator(runnerSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT)
            // The client may have left while we were connecting
            if (!run.client.isOpen) return close(runnerSession, CloseStatus.NORMAL)
            val start =
                mapOf(
                    "type" to "start",
                    "content" to snippet.content,
                    "version" to snippet.version,
                    "correlationId" to run.correlationId,
                )
            send(run.runner, TextMessage(json.writeValueAsString(start)))
        } catch (e: Exception) {
            log.error("Could not start the run of snippet {}", snippetId, e)
            fail(run, "Could not start the run")
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY)
            MDC.remove(UserMdcFilter.MDC_KEY)
        }
    }

    /** Ends the run with `error` and `done error`, as the Runner would. */
    private fun fail(
        run: Run,
        message: String,
    ) {
        send(
            run.client,
            TextMessage(
                json.writeValueAsString(
                    mapOf(
                        "type" to "error",
                        "message" to message,
                        "diagnostics" to emptyList<Any>(),
                    ),
                ),
            ),
        )
        send(run.client, TextMessage(json.writeValueAsString(mapOf("type" to "done", "status" to "error"))))
        close(run.client, CloseStatus.SERVER_ERROR)
        run.runner?.let { close(it, CloseStatus.NORMAL) }
    }

    /** The client side of the socket to the Runner: what comes in goes to the user as is. */
    private inner class RunnerRelay(
        private val run: Run,
    ) : TextWebSocketHandler() {
        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
            send(run.client, message)
        }

        override fun afterConnectionClosed(
            session: WebSocketSession,
            status: CloseStatus,
        ) {
            // 1005 and 1006 can't be sent: the Runner went away without a proper close
            val relayed = if (status.code == NO_STATUS || status.code == ABNORMAL) CloseStatus.SERVER_ERROR else status
            close(run.client, relayed)
        }

        override fun handleTransportError(
            session: WebSocketSession,
            exception: Throwable,
        ) {
            log.warn("Socket to the Runner failed", exception)
            close(run.client, CloseStatus.SERVER_ERROR)
        }
    }

    private fun snippetIdOf(session: WebSocketSession): UUID? {
        val path = session.uri?.path ?: return null
        val id = PATH_TEMPLATE.match(path)["snippetId"] ?: return null
        return runCatching { UUID.fromString(id) }.getOrNull()
    }

    private fun send(
        session: WebSocketSession?,
        message: TextMessage,
    ) {
        if (session == null || !session.isOpen) return
        try {
            session.sendMessage(message)
        } catch (e: Exception) {
            // Closed meanwhile, or too slow (the decorator closes it); the close handlers clean up
            log.debug("Could not send to a socket", e)
        }
    }

    private fun close(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        try {
            if (session.isOpen) session.close(status)
        } catch (e: IOException) {
            log.debug("Could not close a socket", e)
        }
    }

    @PreDestroy
    fun shutdown() {
        timer.shutdownNow()
    }

    companion object {
        const val PATH_PATTERN = "/ws/snippets/*/run"
        private val PATH_TEMPLATE = UriTemplate("/ws/snippets/{snippetId}/run")

        val UNAUTHENTICATED = CloseStatus(4401, "Send a start with a valid token first")
        val FORBIDDEN = CloseStatus(4403, "No READ permission on this snippet")
        val NOT_FOUND = CloseStatus(4404, "Snippet not found")
        val ALREADY_RUNNING = CloseStatus(4409, "A run is already in progress")

        private const val NO_STATUS = 1005
        private const val ABNORMAL = 1006
        private const val SEND_TIME_LIMIT_MS = 10_000
        private const val SEND_BUFFER_LIMIT = 512 * 1024
    }
}
