package org.snippet.snippet.clients.runner

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import org.snippet.snippet.clients.upstream
import org.snippet.snippet.common.error.BadRequestException
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
    /** Version of the PrintScript library the Runner uses (US-18). Null if the Runner doesn't say. */
    val parserVersion: String? = null,
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

enum class TestStatus(
    @get:JsonValue val value: String,
) {
    SUCCESS("success"),
    FAIL("fail"),
    ;

    companion object {
        fun fromValue(value: String): TestStatus =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Unknown test status '$value'")
    }
}

/** What `POST /test` answers: the outputs it got and, if the code failed to run, why. */
data class TestResult(
    val status: TestStatus,
    val outputs: List<String>,
    val errors: List<Diagnostic> = emptyList(),
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

    /**
     * Checks [rules] with the Runner's own validation (unknown names, values out of range,
     * opposite rules both active), so they're never duplicated here. Throws
     * [BadRequestException] with the Runner's reason when they're invalid.
     */
    fun validateRules(
        ruleType: RuleType,
        rules: List<RuleConfig>,
        version: String,
    )

    /** Runs the code consuming [inputs] in order and compares each `println` with [expectedOutputs]. */
    fun test(
        content: String,
        version: String,
        inputs: List<String>,
        expectedOutputs: List<String>,
    ): TestResult
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

    override fun validateRules(
        ruleType: RuleType,
        rules: List<RuleConfig>,
        version: String,
    ) {
        // The contract has no endpoint just for this: run the rules over a snippet that always
        // parses. The Runner checks the rules before looking at the code, so a 400 is about them.
        upstream(SERVICE) {
            try {
                client
                    .post()
                    .uri("/{ruleType}", ruleType.path)
                    .body(RulesRequest(RULES_PROBE, version, rules.map { it.copy(description = null) }))
                    .retrieve()
                    .toBodilessEntity()
            } catch (e: HttpClientErrorException.BadRequest) {
                val detail = runCatching { e.getResponseBodyAs(RunnerProblem::class.java)?.detail }.getOrNull()
                throw BadRequestException(detail ?: "Invalid ${ruleType.path} rules")
            }
        }
    }

    override fun test(
        content: String,
        version: String,
        inputs: List<String>,
        expectedOutputs: List<String>,
    ): TestResult =
        upstream(SERVICE) {
            client
                .post()
                .uri("/test")
                .body(TestRequest(content, version, inputs, expectedOutputs))
                .retrieve()
                .body<RunnerTestResult>()
        }.let { TestResult(it.status, it.outputs, it.errors.orEmpty()) }

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

    private data class TestRequest(
        val content: String,
        val version: String,
        val inputs: List<String>,
        val expectedOutputs: List<String>,
    )

    // `errors` is optional in the contract
    private data class RunnerTestResult(
        val status: TestStatus,
        val outputs: List<String>,
        val errors: List<Diagnostic>? = null,
    )

    private data class RunnerProblem(
        val detail: String? = null,
        val diagnostics: List<Diagnostic>? = null,
    )

    companion object {
        const val SERVICE = "PrintScript Runner"

        /** Valid in every PrintScript version. */
        private const val RULES_PROBE = "println(1);"
    }
}
