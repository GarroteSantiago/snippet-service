package org.snippet.snippet.common.http

import com.fasterxml.jackson.annotation.JsonProperty
import org.snippet.snippet.clients.upstream
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An OAuth2 Client Credentials token of [clientId] for [audience], kept until shortly before it
 * expires: one token request per expiry, shared by every thread.
 *
 * [client] must not carry the [OutboundHeadersInterceptor], so no other token reaches [tokenUri].
 * A failing token request is an [org.snippet.snippet.common.error.UpstreamException] of [service].
 */
class ClientCredentialsToken(
    private val client: RestClient,
    private val service: String,
    private val tokenUri: String,
    private val clientId: String,
    private val clientSecret: String,
    private val audience: String,
    private val clock: Clock,
) {
    private val lock = ReentrantLock()

    @Volatile
    private var token: CachedToken? = null

    /** The cached token, or a new one if there is none or it's about to expire. */
    fun get(): String {
        token?.takeIf { it.isFresh() }?.let { return it.value }
        return lock.withLock {
            // Another thread may have fetched it while this one waited
            token?.takeIf { it.isFresh() }?.value ?: fetch().also { token = it }.value
        }
    }

    /** Forgets the token (revoked, rotated secret), so the next [get] asks for another. */
    fun invalidate() {
        token = null
    }

    private fun fetch(): CachedToken {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "client_credentials")
                add("client_id", clientId)
                add("client_secret", clientSecret)
                add("audience", audience)
            }
        val response =
            upstream(service) {
                client
                    .post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body<TokenResponse>()
            }
        val lifetime = Duration.ofSeconds(response.expiresIn)
        // Renew a bit before it expires, so a request doesn't leave with a token about to die
        val margin = minOf(EXPIRY_MARGIN, lifetime.dividedBy(2))
        return CachedToken(response.accessToken, clock.instant().plus(lifetime).minus(margin))
    }

    private inner class CachedToken(
        val value: String,
        val renewAt: Instant,
    ) {
        fun isFresh() = clock.instant().isBefore(renewAt)
    }

    private data class TokenResponse(
        @param:JsonProperty("access_token") val accessToken: String,
        @param:JsonProperty("expires_in") val expiresIn: Long,
    )

    companion object {
        private val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)

        /** [builder] without the [OutboundHeadersInterceptor], for clients that send their own token. */
        fun withoutForwardedToken(builder: RestClient.Builder): RestClient =
            builder
                .clone()
                .requestInterceptors { interceptors -> interceptors.removeIf { it is OutboundHeadersInterceptor } }
                .build()
    }
}
