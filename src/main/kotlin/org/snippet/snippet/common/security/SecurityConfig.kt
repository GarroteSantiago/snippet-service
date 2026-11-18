package org.snippet.snippet.common.security

import org.snippet.snippet.common.logging.UserMdcFilter
import org.snippet.snippet.run.SnippetRunHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain

/**
 * Every endpoint requires a valid Auth0 JWT except the health checks and the handshake of the
 * interactive run, which authenticates in its first message.
 * Signature, `iss`, `aud` and `exp` are validated by Spring from the
 * `spring.security.oauth2.resourceserver.jwt.*` properties.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/health", permitAll)
                authorize("/health/**", permitAll)
                // Interactive run (US-10): browsers can't send headers on a WebSocket, so the handshake is
                // public and the first message carries the JWT, which SnippetRunHandler validates with
                // the same JwtDecoder (contracts/websocket.md)
                authorize(SnippetRunHandler.PATH_PATTERN, permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer { jwt { } }
            addFilterAfter<BearerTokenAuthenticationFilter>(UserMdcFilter())
        }
        return http.build()
    }
}
