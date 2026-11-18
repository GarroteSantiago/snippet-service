package org.snippet.snippet.processing

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import org.snippet.snippet.clients.runner.RuleConfig
import java.util.UUID

// Messages of `contracts/redis-streams.md`. Each stream entry has the fields
// `type`, `correlationId` and `payload` (JSON, one of the classes below).

object Streams {
    const val JOBS = "snippet:jobs"
    const val RESULTS = "snippet:results"
    const val GROUP = "snippet-service"

    const val TYPE = "type"
    const val CORRELATION_ID = "correlationId"
    const val PAYLOAD = "payload"
}

enum class JobType { VALIDATE, LINT, FORMAT, TEST }

/** Payload of a VALIDATE, LINT or FORMAT job. [rules] only for LINT and FORMAT. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JobMessage(
    val jobId: UUID,
    val snippetId: UUID,
    val version: String,
    val content: String,
    val rules: List<RuleConfig>? = null,
)

/** Payload of a TEST job (US-16): one per test of the snippet. */
data class TestJobMessage(
    val jobId: UUID,
    val snippetId: UUID,
    val testId: UUID,
    val version: String,
    val content: String,
    val inputs: List<String>,
    val expectedOutputs: List<String>,
)

enum class ResultStatus { OK, FAILED }

/** Payload of `snippet:results`. [result] depends on the type and is absent when FAILED. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ResultMessage(
    val jobId: UUID,
    val snippetId: UUID,
    val status: ResultStatus,
    val error: String? = null,
    val result: JsonNode? = null,
)
