package org.snippet.snippet.common.http

import org.slf4j.MDC
import org.snippet.snippet.common.logging.CorrelationIdFilter
import org.springframework.boot.web.client.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Calls to other services carry the correlation id and the caller's token,
 * as the contracts require. Applied to every `RestClient.Builder` Spring creates.
 */
class OutboundHeadersInterceptor : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        MDC.get(CorrelationIdFilter.MDC_KEY)?.let { request.headers.set(CorrelationIdFilter.HEADER, it) }
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken && !request.headers.containsKey(HttpHeaders.AUTHORIZATION)) {
            request.headers.setBearerAuth(authentication.token.tokenValue)
        }
        return execution.execute(request, body)
    }
}

@Configuration
class RestClientConfig {
    @Bean
    fun outboundHeadersCustomizer(): RestClientCustomizer = RestClientCustomizer { it.requestInterceptor(OutboundHeadersInterceptor()) }
}
