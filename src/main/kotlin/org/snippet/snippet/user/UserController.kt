package org.snippet.snippet.user

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.snippet.snippet.clients.auth0.UserDirectory
import org.snippet.snippet.clients.auth0.UserSummary
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** `UserPage` in the public contract. */
data class UserPage(
    val page: Int,
    @get:JsonProperty("page_size")
    val pageSize: Int,
    val count: Long,
    val users: List<UserSummary>,
)

/** `GET /users`: who the caller can share a snippet with (US-07). The caller is left out. */
@RestController
class UserController(
    private val users: UserDirectory,
) {
    @GetMapping("/users")
    fun search(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) name: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(name = "page_size", defaultValue = "10") @Min(1) @Max(100) pageSize: Int,
    ): UserPage {
        val result = users.search(name, jwt.subject, page, pageSize)
        return UserPage(page, pageSize, result.count, result.users)
    }
}
