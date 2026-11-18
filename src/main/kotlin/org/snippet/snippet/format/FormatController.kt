package org.snippet.snippet.format

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.language.LanguageCatalog
import org.snippet.snippet.rules.RuleStore
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class FormatRequest(
    val content: String,
    @field:NotBlank
    val language: String,
    @field:NotBlank
    val version: String,
)

data class FormatResponse(
    val content: String,
)

@Service
class FormatService(
    private val runner: RunnerClient,
    private val languages: LanguageCatalog,
    private val ruleStore: RuleStore,
) {
    /** Formats with [userId]'s format rules (US-11). Invalid code comes back from the Runner as 422 with its diagnostics. */
    fun format(
        request: FormatRequest,
        userId: String,
    ): String {
        languages.requireSupported(request.language, request.version)
        val rules = ruleStore.rulesOf(userId, RuleType.FORMAT)
        return runner.format(request.content, request.version, rules)
    }
}

@RestController
class FormatController(
    private val service: FormatService,
) {
    @PostMapping("/format")
    fun format(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: FormatRequest,
    ): FormatResponse = FormatResponse(service.format(request, jwt.subject))
}
