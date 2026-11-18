package org.snippet.snippet.common.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Reads `X-Correlation-Id` (NGINX or the calling service sets it), or creates one,
 * and keeps it in the MDC for the whole request so every log line carries it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId =
            request
                .getHeader(HEADER)
                ?.takeIf { isSafe(it) }
                ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, correlationId)
        response.setHeader(HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val MDC_KEY = "correlationId"

        // Anything else is replaced, so a client can't inject text into the logs
        private val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")

        /** Whether [id] can go to the logs as is. */
        fun isSafe(id: String) = SAFE_ID.matches(id)
    }
}
