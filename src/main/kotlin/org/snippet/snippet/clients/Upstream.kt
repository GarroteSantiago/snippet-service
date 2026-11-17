package org.snippet.snippet.clients

import org.snippet.snippet.common.error.UpstreamException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/**
 * Runs a call to [service] and turns transport errors, unexpected statuses and empty
 * bodies into [UpstreamException], so callers only see the API's own errors.
 */
internal fun <T : Any> upstream(
    service: String,
    request: () -> T?,
): T =
    try {
        request() ?: throw UpstreamException(service, "empty response")
    } catch (e: RestClientResponseException) {
        throw UpstreamException(service, "answered ${e.statusCode}", e)
    } catch (e: RestClientException) {
        throw UpstreamException(service, e.message ?: "call failed", e)
    }
