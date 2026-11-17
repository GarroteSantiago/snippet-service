package org.snippet.snippet.common.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Puts the authenticated user (the JWT `sub`) in the MDC. Registered inside the
 * security chain, right after the bearer token is authenticated.
 */
class UserMdcFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val user =
            SecurityContextHolder
                .getContext()
                .authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?.name
        user?.let { MDC.put(MDC_KEY, it) }
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val MDC_KEY = "user"
    }
}
