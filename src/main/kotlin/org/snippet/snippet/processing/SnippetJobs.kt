package org.snippet.snippet.processing

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.common.logging.CorrelationIdFilter
import org.snippet.snippet.common.streams.StreamClient
import org.snippet.snippet.common.streams.StreamProperties
import org.snippet.snippet.rules.RuleStore
import org.snippet.snippet.snippet.Compliance
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetRepository
import org.snippet.snippet.snippet.SnippetService
import org.snippet.snippet.testcase.TestCaseRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Producer of `snippet:jobs` (`contracts/redis-streams.md`). Each job's id is stored as the latest
 * of its type for the snippet *before* publishing it, so its result can never look stale.
 *
 * LINT and VALIDATE both decide `compliance`, so publishing one forgets the other: a result of the
 * older one still on its way is ignored and the newest job wins.
 *
 * Publishing can't fail a user request: if a LINT or VALIDATE can't be published the snippet is
 * left `failed` (it would otherwise stay `pending` forever); a FORMAT that can't is only logged.
 */
@Service
class SnippetJobs(
    private val tracker: JobTracker,
    private val streams: StreamClient,
    private val properties: StreamProperties,
    private val rules: RuleStore,
    private val repository: SnippetRepository,
    private val assets: AssetClient,
    private val testCases: TestCaseRepository,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** LINT with the owner's rules (or [lintRules] if given). */
    fun lint(
        snippet: Snippet,
        content: String,
        lintRules: List<RuleConfig>? = null,
    ) {
        try {
            val ruleList = lintRules ?: rules.rulesOf(snippet.ownerId, RuleType.LINT)
            tracker.forget(snippet.id, JobType.VALIDATE)
            publish(JobType.LINT, snippet, content, ruleList)
        } catch (e: Exception) {
            log.error("Could not publish the LINT of snippet {}; it's left failed", snippet.id, e)
            markFailed(snippet)
        }
    }

    /** FORMAT with the owner's rules (or [formatRules] if given). */
    fun format(
        snippet: Snippet,
        content: String,
        formatRules: List<RuleConfig>? = null,
    ) {
        try {
            publish(JobType.FORMAT, snippet, content, formatRules ?: rules.rulesOf(snippet.ownerId, RuleType.FORMAT))
        } catch (e: Exception) {
            log.error("Could not publish the FORMAT of snippet {}", snippet.id, e)
        }
    }

    fun validate(
        snippet: Snippet,
        content: String,
    ) {
        try {
            tracker.forget(snippet.id, JobType.LINT)
            publish(JobType.VALIDATE, snippet, content, rules = null)
        } catch (e: Exception) {
            log.error("Could not publish the VALIDATE of snippet {}; it's left failed", snippet.id, e)
            markFailed(snippet)
        }
    }

    /**
     * US-16: a TEST for each test of the snippet. Each job id is stored on its test before
     * publishing, so only the newest run of a test can set its `lastResult`. A test that
     * can't be published keeps its previous result; that is only logged.
     */
    fun tests(
        snippet: Snippet,
        content: String,
    ) {
        testCases.findBySnippetIdOrderByCreatedAtAscIdAsc(snippet.id).forEach { test ->
            try {
                val jobId = UUID.randomUUID()
                testCases.updateLastJob(test.id, jobId)
                add(
                    JobType.TEST,
                    TestJobMessage(jobId, snippet.id, test.id, snippet.version, content, test.inputs, test.expectedOutputs),
                )
            } catch (e: Exception) {
                log.error("Could not publish the TEST {} of snippet {}", test.id, snippet.id, e)
            }
        }
    }

    /** After `PUT /rules/lint`: every snippet of the owner goes to `pending` and gets a LINT. */
    fun lintAllOf(
        ownerId: String,
        lintRules: List<RuleConfig>,
    ) {
        val snippets = repository.findAllByOwnerId(ownerId)
        snippets.forEach {
            it.compliance = Compliance.PENDING
            it.lintViolations = mutableListOf()
            repository.save(it)
        }
        snippets.forEach { snippet -> withContent(snippet, onMissing = ::markFailed) { lint(snippet, it, lintRules) } }
        log.info("Published LINT for {} snippets of {}", snippets.size, ownerId)
    }

    /** After `PUT /rules/format`: a FORMAT for every snippet of the owner. */
    fun formatAllOf(
        ownerId: String,
        formatRules: List<RuleConfig>,
    ) {
        val snippets = repository.findAllByOwnerId(ownerId)
        snippets.forEach { snippet -> withContent(snippet, onMissing = {}) { format(snippet, it, formatRules) } }
        log.info("Published FORMAT for {} snippets of {}", snippets.size, ownerId)
    }

    /** A new `parserVersion` (US-18): a VALIDATE for every snippet. */
    fun validateAll() {
        val snippets = repository.findAll()
        snippets.forEach { snippet -> withContent(snippet, onMissing = ::markFailed) { validate(snippet, it) } }
        log.info("Published VALIDATE for {} snippets", snippets.size)
    }

    private fun publish(
        type: JobType,
        snippet: Snippet,
        content: String,
        rules: List<RuleConfig>?,
    ) {
        val jobId = UUID.randomUUID()
        val payload = JobMessage(jobId, snippet.id, snippet.version, content, rules?.map { it.copy(description = null) })
        tracker.record(snippet.id, type, jobId)
        add(type, payload)
    }

    private fun add(
        type: JobType,
        payload: Any,
    ) {
        streams.add(
            Streams.JOBS,
            mapOf(
                Streams.TYPE to type.name,
                Streams.CORRELATION_ID to (MDC.get(CorrelationIdFilter.MDC_KEY) ?: UUID.randomUUID().toString()),
                Streams.PAYLOAD to json.writeValueAsString(payload),
            ),
            properties.maxLength,
        )
    }

    /** Runs [publish] with the stored content; a snippet whose content can't be read goes to [onMissing]. */
    private fun withContent(
        snippet: Snippet,
        onMissing: (Snippet) -> Unit,
        publish: (String) -> Unit,
    ) {
        val content =
            try {
                assets.get(SnippetService.CONTENT, snippet.id.toString())
            } catch (e: Exception) {
                log.error("Could not read the content of snippet {}", snippet.id, e)
                null
            }
        if (content == null) onMissing(snippet) else publish(content)
    }

    private fun markFailed(snippet: Snippet) {
        try {
            snippet.compliance = Compliance.FAILED
            repository.save(snippet)
        } catch (e: Exception) {
            log.error("Could not mark snippet {} as failed", snippet.id, e)
        }
    }
}
