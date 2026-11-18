package org.snippet.snippet.processing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.treeToValue
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.clients.runner.TestStatus
import org.snippet.snippet.common.http.BackgroundCalls
import org.snippet.snippet.common.logging.CorrelationIdFilter
import org.snippet.snippet.common.streams.StreamEntry
import org.snippet.snippet.common.streams.StreamHandler
import org.snippet.snippet.snippet.Compliance
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetRepository
import org.snippet.snippet.snippet.SnippetService
import org.snippet.snippet.snippet.StoredViolation
import org.snippet.snippet.testcase.TestCaseRepository
import org.springframework.data.repository.findByIdOrNull
import java.time.Instant
import java.util.UUID

/**
 * Applies each result of `snippet:results` (table "Cuándo se publica y qué hace cada resultado" in
 * `contracts/redis-streams.md`). The worker acks it after this returns, i.e. after it's stored;
 * throwing (database or Asset Service down) leaves it pending to be retried.
 *
 * A result whose jobId isn't the latest published for its snippet and type is stale and only
 * acked; so is one for a snippet that was deleted. Applying the same result twice gives the same
 * state (a VALIDATE publishes a new LINT, which supersedes the previous one).
 */
class ResultHandler(
    private val repository: SnippetRepository,
    private val tracker: JobTracker,
    private val jobs: SnippetJobs,
    private val assets: AssetClient,
    private val background: BackgroundCalls,
    private val testCases: TestCaseRepository,
    private val json: ObjectMapper,
) : StreamHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(entry: StreamEntry) {
        withCorrelationId(entry) {
            val (type, result) = parse(entry) ?: return@withCorrelationId
            if (type == JobType.TEST) {
                applyTest(result)
                return@withCorrelationId
            }
            val snippet = current(type, result) ?: return@withCorrelationId
            when (type) {
                JobType.LINT -> applyLint(snippet, result)
                JobType.FORMAT -> applyFormat(snippet, result)
                JobType.VALIDATE -> applyValidate(snippet, result)
                JobType.TEST -> Unit
            }
        }
    }

    /** Gave up on the result: LINT and VALIDATE leave the snippet `failed` (if it's still the latest job). */
    override fun discard(
        entry: StreamEntry,
        deliveries: Long,
    ) {
        withCorrelationId(entry) {
            val (type, result) = parse(entry) ?: return@withCorrelationId
            if (type != JobType.LINT && type != JobType.VALIDATE) return@withCorrelationId
            current(type, result)?.let { setCompliance(it, Compliance.FAILED) }
        }
    }

    private fun applyLint(
        snippet: Snippet,
        result: ResultMessage,
    ) {
        if (result.status == ResultStatus.FAILED) {
            log.warn("LINT of snippet {} failed: {}", snippet.id, result.error)
            setCompliance(snippet, Compliance.FAILED)
            return
        }
        val violations = diagnostics(result, "violations")
        setCompliance(snippet, if (violations.isEmpty()) Compliance.COMPLIANT else Compliance.NOT_COMPLIANT, violations)
    }

    /** US-16: the test's `lastResult`, only if this is still its latest TEST job; FAILED is only logged. */
    private fun applyTest(result: ResultMessage) {
        if (result.status == ResultStatus.FAILED) {
            log.warn("TEST job {} of snippet {} failed: {}", result.jobId, result.snippetId, result.error)
            return
        }
        val testId =
            result.result
                ?.get("testId")
                ?.asText()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val status =
            result.result
                ?.get("status")
                ?.asText()
                ?.let { runCatching { TestStatus.fromValue(it) }.getOrNull() }
        if (testId == null || status == null) return log.error("TEST result {} has no valid testId or status", result.jobId)
        if (testCases.updateResultOfJob(testId, result.jobId, status, Instant.now()) == 0) {
            log.info("Ignoring TEST result {}: test {} is gone or has a newer run", result.jobId, testId)
        }
    }

    private fun applyFormat(
        snippet: Snippet,
        result: ResultMessage,
    ) {
        if (result.status == ResultStatus.FAILED) {
            log.warn("FORMAT of snippet {} failed: {}", snippet.id, result.error)
            return
        }
        val content = result.result?.get("content")?.asText() ?: return log.error("FORMAT result {} has no content", result.jobId)
        background.call { assets.put(SnippetService.FORMATTED, snippet.id.toString(), content) }
    }

    private fun applyValidate(
        snippet: Snippet,
        result: ResultMessage,
    ) {
        if (result.status == ResultStatus.FAILED) {
            log.warn("VALIDATE of snippet {} failed: {}", snippet.id, result.error)
            setCompliance(snippet, Compliance.FAILED)
            return
        }
        if (result.result?.get("valid")?.asBoolean() == true) {
            // Still valid with the new parser: lint it again (this also forgets the VALIDATE job)
            val content = background.call { assets.get(SnippetService.CONTENT, snippet.id.toString()) }
            if (content == null) {
                log.error("Snippet {} has no content to lint", snippet.id)
                setCompliance(snippet, Compliance.FAILED)
            } else {
                background.call { jobs.lint(snippet, content) }
            }
        } else {
            // Not valid anymore: kept, with the parser's diagnostics as violations
            setCompliance(snippet, Compliance.NOT_COMPLIANT, diagnostics(result, "diagnostics").map { it.copy(rule = null) })
        }
    }

    /** The snippet, unless it's gone or [result] is not its latest job of [type]. */
    private fun current(
        type: JobType,
        result: ResultMessage,
    ): Snippet? {
        val snippet = repository.findByIdOrNull(result.snippetId)
        if (snippet == null) {
            log.info("Ignoring {} result {}: snippet {} no longer exists", type, result.jobId, result.snippetId)
            return null
        }
        if (tracker.latest(snippet.id, type) != result.jobId) {
            log.info("Ignoring stale {} result {} of snippet {}", type, result.jobId, snippet.id)
            return null
        }
        return snippet
    }

    private fun setCompliance(
        snippet: Snippet,
        compliance: Compliance,
        violations: List<Diagnostic> = emptyList(),
    ) {
        snippet.compliance = compliance
        snippet.lintViolations = violations.map { StoredViolation(it.rule, it.message, it.line, it.column) }.toMutableList()
        repository.save(snippet)
        log.info("Snippet {} is {}", snippet.id, compliance.value)
    }

    private fun diagnostics(
        result: ResultMessage,
        field: String,
    ): List<Diagnostic> =
        result.result
            ?.get(field)
            ?.let { json.treeToValue<List<Diagnostic>>(it) }
            .orEmpty()

    /** null (logged) when the entry can't be understood; it's acked, since retrying won't help. */
    private fun parse(entry: StreamEntry): Pair<JobType, ResultMessage>? {
        val type = JobType.entries.find { it.name == entry.fields[Streams.TYPE] }
        val result = runCatching { json.readValue<ResultMessage>(entry.fields[Streams.PAYLOAD].orEmpty()) }.getOrNull()
        if (type == null || result == null) {
            log.error("Dropping malformed result {} (type {})", entry.id, entry.fields[Streams.TYPE])
            return null
        }
        return type to result
    }

    private inline fun withCorrelationId(
        entry: StreamEntry,
        block: () -> Unit,
    ) {
        entry.fields[Streams.CORRELATION_ID]?.let { MDC.put(CorrelationIdFilter.MDC_KEY, it) }
        try {
            block()
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY)
        }
    }
}
