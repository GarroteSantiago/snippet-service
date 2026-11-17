package org.snippet.snippet.common.security

import org.snippet.snippet.common.logging.UserMdcFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain

/**
 * Every endpoint requires a valid Auth0 JWT except the health checks.
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
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer { jwt { } }
            addFilterAfter<BearerTokenAuthenticationFilter>(UserMdcFilter())
        }
        return http.build()
    }
}
