package org.snippet.snippet.common.security

import org.springframework.security.oauth2.jwt.Jwt

/**
 * The caller. [id] is the JWT `sub`. [name] is what we show as the author of their
 * snippets: Auth0 access tokens only carry a name if a rule/action adds it, so we
 * take the first of `name`, `nickname` and `email` that is present, else the `sub`.
 */
data class CurrentUser(
    val id: String,
    val name: String,
) {
    companion object {
        private val NAME_CLAIMS = listOf("name", "nickname", "email")

        fun from(jwt: Jwt): CurrentUser {
            val name = NAME_CLAIMS.firstNotNullOfOrNull { claim -> jwt.getClaimAsString(claim)?.takeIf { it.isNotBlank() } }
            return CurrentUser(jwt.subject, name ?: jwt.subject)
        }
    }
}
