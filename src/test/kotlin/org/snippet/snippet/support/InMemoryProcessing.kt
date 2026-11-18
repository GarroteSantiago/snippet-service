package org.snippet.snippet.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.common.streams.StreamProperties
import org.snippet.snippet.processing.JobTracker
import org.snippet.snippet.processing.JobType
import org.snippet.snippet.processing.ParserVersionStore
import org.snippet.snippet.processing.SnippetJobs
import org.snippet.snippet.processing.Streams
import org.snippet.snippet.rules.RuleRepository
import org.snippet.snippet.rules.RuleStore
import org.snippet.snippet.snippet.SnippetRepository
import java.util.UUID

class InMemoryJobTracker : JobTracker {
    val latest = mutableMapOf<Pair<UUID, JobType>, UUID>()

    override fun record(
        snippetId: UUID,
        type: JobType,
        jobId: UUID,
    ) {
        latest[snippetId to type] = jobId
    }

    override fun latest(
        snippetId: UUID,
        type: JobType,
    ) = latest[snippetId to type]

    override fun forget(
        snippetId: UUID,
        type: JobType,
    ) {
        latest.remove(snippetId to type)
    }
}

class InMemoryRuleRepository : RuleRepository {
    val rules = mutableMapOf<Pair<String, RuleType>, List<RuleConfig>>()

    override fun find(
        ownerId: String,
        type: RuleType,
    ) = rules[ownerId to type]

    override fun save(
        ownerId: String,
        type: RuleType,
        rules: List<RuleConfig>,
    ) {
        this.rules[ownerId to type] = rules
    }
}

class InMemoryParserVersionStore : ParserVersionStore {
    var version: String? = null

    override fun current() = version

    override fun change(version: String): Boolean {
        if (this.version == version) return false
        this.version = version
        return true
    }
}

/** The pieces of the processing pipeline wired over fakes, plus helpers to read what was published. */
class ProcessingFixture(
    val repository: InMemorySnippetRepository = InMemorySnippetRepository(),
    val runner: FakeRunner = FakeRunner(),
    val assets: FakeAssets = FakeAssets(),
) {
    val json: ObjectMapper = jacksonObjectMapper()
    val streams = FakeStreamClient()
    val tracker = InMemoryJobTracker()
    val rules = InMemoryRuleRepository()
    val ruleStore = RuleStore(rules, runner as RunnerClient)
    val testCases = InMemoryTestCaseRepository()
    val jobs =
        SnippetJobs(
            tracker,
            streams,
            StreamProperties(),
            ruleStore,
            repository as SnippetRepository,
            assets as AssetClient,
            testCases,
            json,
        )

    /** Jobs published so far, as (type, payload). */
    fun publishedJobs(): List<Pair<String, JsonNode>> =
        streams.entries(Streams.JOBS).map { it.fields.getValue(Streams.TYPE) to json.readTree(it.fields.getValue(Streams.PAYLOAD)) }

    fun jobsOf(
        snippetId: UUID,
        type: String,
    ) = publishedJobs().filter { (t, payload) -> t == type && payload["snippetId"].asText() == snippetId.toString() }.map { it.second }
}
