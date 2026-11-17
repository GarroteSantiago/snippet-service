package org.snippet.snippet.clients.runner

import com.fasterxml.jackson.annotation.JsonInclude
import org.snippet.snippet.clients.upstream
import org.snippet.snippet.common.error.InvalidCodeException
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body

/** A parser error or a lint violation, as in every contract. `rule` is null for syntax errors. */
data class Diagnostic(
    val rule: String? = null,
    val message: String,
    val line: Int,
    val column: Int,
)

data class Versions(
    val language: String,
    val versions: List<String>,
    val latest: String,
)

data class ValidationResult(
    val valid: Boolean,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** A rule as the Runner takes it in `rules`. `value` is a string, a number or null. */
data class RuleConfig(
    val name: String,
    val isActive: Boolean,
    val value: Any? = null,
    // Only in the catalog (`GET /rules/{ruleType}`); never sent back
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val description: String? = null,
)

enum class RuleType(
    val path: String,
) {
    FORMAT("format"),
    LINT("lint"),
}

/** PrintScript Runner (`contracts/printscript-runner.openapi.yaml`). */
interface RunnerClient {
    fun versions(): Versions

    /** Invalid code is a normal answer (`valid = false`), not an error. */
    fun validate(
        content: String,
        version: String,
    ): ValidationResult

    fun defaultRules(ruleType: RuleType): List<RuleConfig>

    /** Throws [InvalidCodeException] when the code doesn't parse. */
    fun format(
        content: String,
        version: String,
        rules: List<RuleConfig>,
    ): String
}

@Component
class RestRunnerClient(
    builder: RestClient.Builder,
    @Value("\${services.runner.url}") baseUrl: String,
) : RunnerClient {
    private val client = builder.baseUrl(baseUrl).build()

    override fun versions(): Versions =
        upstream(SERVICE) {
            client
                .get()
                .uri("/versions")
                .retrieve()
                .body<Versions>()
        }

    override fun validate(
        content: String,
        version: String,
    ): ValidationResult =
        upstream(SERVICE) {
            client
                .post()
                .uri("/validate")
                .body(CodeRequest(content, version))
                .retrieve()
                .body<ValidationResult>()
        }

    override fun defaultRules(ruleType: RuleType): List<RuleConfig> =
        upstream(SERVICE) {
            client
                .get()
                .uri("/rules/{ruleType}", ruleType.path)
                .retrieve()
                .body(object : ParameterizedTypeReference<List<RuleConfig>>() {})
        }

    override fun format(
        content: String,
        version: String,
        rules: List<RuleConfig>,
    ): String =
        upstream(SERVICE) {
            try {
                client
                    .post()
                    .uri("/format")
                    .body(RulesRequest(content, version, rules.map { it.copy(description = null) }))
                    .retrieve()
                    .body<FormatResult>()
            } catch (e: HttpClientErrorException.UnprocessableEntity) {
                // The code doesn't parse: pass the Runner's diagnostics through
                throw InvalidCodeException(e.diagnostics())
            }
        }.content

    private fun RestClientResponseException.diagnostics(): List<Diagnostic> =
        runCatching { getResponseBodyAs(RunnerProblem::class.java)?.diagnostics }.getOrNull().orEmpty()

    private data class CodeRequest(
        val content: String,
        val version: String,
    )

    private data class RulesRequest(
        val content: String,
        val version: String,
        val rules: List<RuleConfig>,
    )

    private data class FormatResult(
        val content: String,
    )

    private data class RunnerProblem(
        val diagnostics: List<Diagnostic>? = null,
    )

    companion object {
        const val SERVICE = "PrintScript Runner"
    }
}
