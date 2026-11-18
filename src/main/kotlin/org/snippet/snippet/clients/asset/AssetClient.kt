package org.snippet.snippet.clients.asset

import org.snippet.snippet.clients.upstream
import org.snippet.snippet.clients.withToken
import org.snippet.snippet.common.error.UpstreamException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * The course's Asset Service: `PUT/GET/DELETE /v1/asset/{container}/{key}`.
 * Content is stored as UTF-8 bytes.
 */
interface AssetClient {
    /** Creates or overwrites. */
    fun put(
        container: String,
        key: String,
        content: String,
    )

    /**
     * null if there is no such asset. [token] is the user's token for the call; null takes the one
     * of the current HTTP request (WebSocket threads have none, so they pass it).
     */
    fun get(
        container: String,
        key: String,
        token: String? = null,
    ): String?

    /** Idempotent: a missing asset is not an error. */
    fun delete(
        container: String,
        key: String,
    )
}

@Component
class RestAssetClient(
    builder: RestClient.Builder,
    @Value("\${services.asset.url}") baseUrl: String,
) : AssetClient {
    private val client = builder.baseUrl(baseUrl).build()

    // Answers 201 when it creates and 200 when it overwrites; both are fine
    override fun put(
        container: String,
        key: String,
        content: String,
    ) {
        upstream(SERVICE) {
            client
                .put()
                .uri(PATH, container, key)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content.toByteArray(Charsets.UTF_8))
                .retrieve()
                .toBodilessEntity()
        }
    }

    override fun get(
        container: String,
        key: String,
        token: String?,
    ): String? =
        try {
            // An empty asset comes back without a body
            upstream(SERVICE) {
                client
                    .get()
                    .uri(PATH, container, key)
                    .withToken(token)
                    .accept(MediaType.ALL)
                    .retrieve()
                    .body<ByteArray>() ?: ByteArray(0)
            }.toString(Charsets.UTF_8)
        } catch (e: UpstreamException) {
            if (e.cause is HttpClientErrorException.NotFound) null else throw e
        }

    // Answers 204, or 404 if it wasn't there
    override fun delete(
        container: String,
        key: String,
    ) {
        try {
            upstream(SERVICE) {
                client
                    .delete()
                    .uri(PATH, container, key)
                    .retrieve()
                    .toBodilessEntity()
            }
        } catch (e: UpstreamException) {
            if (e.cause !is HttpClientErrorException.NotFound) throw e
        }
    }

    companion object {
        const val SERVICE = "Asset Service"
        private const val PATH = "/v1/asset/{container}/{key}"
    }
}
