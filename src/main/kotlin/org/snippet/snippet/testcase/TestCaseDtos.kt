package org.snippet.snippet.testcase

import jakarta.validation.constraints.NotBlank
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.clients.runner.TestStatus
import java.util.UUID

/**
 * `TestCaseInput` in the contract: the body to create, edit or run ad hoc. `input` and
 * `output` are optional there; missing means none.
 */
data class TestCaseRequest(
    @field:NotBlank
    val name: String,
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
)

/** `TestCase` in the contract. `lastResult` is null until it runs. */
data class TestCaseResponse(
    val id: UUID,
    val name: String,
    val input: List<String>,
    val output: List<String>,
    val lastResult: TestStatus?,
)

/** `TestRunResult` in the contract. */
data class TestRunResult(
    val status: TestStatus,
    val outputs: List<String>,
    val errors: List<Diagnostic>,
)
