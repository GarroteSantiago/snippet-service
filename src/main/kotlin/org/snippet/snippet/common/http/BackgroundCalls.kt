package org.snippet.snippet.common.http

import org.slf4j.LoggerFactory
import org.snippet.snippet.common.error.UpstreamException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.time.Clock

/**
 * Client Credentials of the Snippet Service itself, for the calls it makes without a user
 * ("Llamadas sin usuario" in `contracts/README.md`). Same issuer and audience as the user
 * tokens. Empty credentials by default so the app starts without them.
 */
@ConfigurationProperties("auth0.service")
data class ServiceTokenProperties(
    val tokenUri: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val audience: String = "",
) {
    val configured: Boolean get() =
        tokenUri.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank() && audience.isNotBlank()
}

@Configuration
@EnableConfigurationProperties(ServiceTokenProperties::class)
class ServiceTokenConfig

/**
 * The only way work without a user request (the `snippet:results` consumer, the `parserVersion`
 * check) calls the Runner or the Asset Service.
 *
 * Inside [call], the [OutboundHeadersInterceptor] sends the service's own Client Credentials
 * token instead of the user's (there is none). Without [token] (credentials not configured)
 * the calls go out without `Authorization` and a service that requires it answers 401, which
 * comes back as [UpstreamException]; that's warned about on startup.
 */
@Component
class BackgroundCalls(
    private val token: ClientCredentialsToken?,
) {
    @Autowired
    constructor(builder: RestClient.Builder, properties: ServiceTokenProperties) : this(clientCredentials(builder, properties))

    init {
        if (token == null) {
            LoggerFactory.getLogger(javaClass).warn(
                "No service credentials (AUTH0_SERVICE_CLIENT_ID, AUTH0_SERVICE_CLIENT_SECRET): calls without a user " +
                    "to the Runner and the Asset Service go out without a token and will be rejected",
            )
        }
    }

    /**
     * Runs [block], which calls other services on behalf of the system, not of a user. Fails
     * with [UpstreamException] if the token can't be obtained, as when a service is down.
     */
    fun <T> call(block: () -> T): T {
        val value = token?.get() ?: return block()
        val previous = CURRENT.get()
        CURRENT.set(value)
        return try {
            block()
        } catch (e: UpstreamException) {
            // Rejected: maybe revoked or the secret rotated, so the next call asks for a new one
            if (e.cause is HttpClientErrorException.Unauthorized) token.invalidate()
            throw e
        } finally {
            if (previous == null) CURRENT.remove() else CURRENT.set(previous)
        }
    }

    companion object {
        const val SERVICE = "Auth0 token endpoint"

        // Per thread (virtual ones too): the block runs on the caller's thread
        private val CURRENT = ThreadLocal<String>()

        /** The service token while inside [call] on this thread, else null. */
        fun currentToken(): String? = CURRENT.get()

        private fun clientCredentials(
            builder: RestClient.Builder,
            properties: ServiceTokenProperties,
        ): ClientCredentialsToken? =
            if (!properties.configured) {
                null
            } else {
                ClientCredentialsToken(
                    client = ClientCredentialsToken.withoutForwardedToken(builder),
                    service = SERVICE,
                    tokenUri = properties.tokenUri,
                    clientId = properties.clientId,
                    clientSecret = properties.clientSecret,
                    audience = properties.audience,
                    clock = Clock.systemUTC(),
                )
            }
    }
}
