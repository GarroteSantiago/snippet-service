package org.snippet.snippet.processing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.common.streams.StreamClient
import org.snippet.snippet.common.streams.StreamStats
import org.snippet.snippet.rules.RuleRepository
import org.snippet.snippet.snippet.Compliance
import org.snippet.snippet.snippet.SnippetRepository
import org.snippet.snippet.snippet.SnippetService.Companion.FORMATTED
import org.snippet.snippet.support.FakeAssets
import org.snippet.snippet.support.FakePermissions
import org.snippet.snippet.support.FakeRunner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pipeline against real Postgres and Redis: publishing jobs, the consumer of `snippet:results`
 * (with the test acting as the Runner), stale results and the JDBC stores of V2 and V3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Needs Docker for Postgres and Redis: skipped where there is none, runs in CI.
@Testcontainers(disabledWithoutDocker = true)
class ProcessingIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val json: ObjectMapper,
    @param:Autowired private val redis: StringRedisTemplate,
    @param:Autowired private val streams: StreamClient,
    @param:Autowired private val repository: SnippetRepository,
    @param:Autowired private val rules: RuleRepository,
    @param:Autowired private val tracker: JobTracker,
    @param:Autowired private val parserVersions: ParserVersionStore,
    @param:Autowired private val assets: FakeAssets,
    @param:Autowired private val meters: MeterRegistry,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @Container
        @JvmStatic
        val redisContainer: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redisContainer.host }
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
            registry.add("streams.enabled") { true }
            registry.add("streams.consumer-name") { "snippet-service-test" }
            registry.add("streams.block") { "200ms" }
        }
    }

    @TestConfiguration
    class FakeServices {
        @Bean
        @Primary
        fun fakeRunner(): RunnerClient = FakeRunner()

        @Bean
        @Primary
        fun fakePermissions(): FakePermissions = FakePermissions()

        @Bean
        @Primary
        fun fakeAssets(): FakeAssets = FakeAssets()
    }

    private val alice = jwt().jwt { it.subject("auth0|alice-${UUID.randomUUID()}") }

    private fun waitFor(
        timeout: Duration = Duration.ofSeconds(10),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out" }
            Thread.sleep(50)
        }
    }

    private fun create(): UUID {
        val body =
            mockMvc
                .post("/snippets") {
                    with(alice)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"hello","language":"printscript","version":"1.1","extension":"ps","content":"println(1);"}"""
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString
        return UUID.fromString(json.readTree(body)["id"].asText())
    }

    private fun jobs(
        snippetId: UUID,
        type: String,
    ): List<JsonNode> =
        redis
            .opsForStream<String, String>()
            .range(Streams.JOBS, Range.unbounded())
            .orEmpty()
            .filter { it.value[Streams.TYPE] == type }
            .map { json.readTree(it.value[Streams.PAYLOAD].toString()) }
            .filter { it["snippetId"].asText() == snippetId.toString() }

    /** What the Runner would answer. */
    private fun answer(
        type: String,
        jobId: String,
        snippetId: UUID,
        result: String,
    ) {
        streams.createGroup(Streams.RESULTS, Streams.GROUP)
        val payload = """{"jobId":"$jobId","snippetId":"$snippetId","status":"OK","result":$result}"""
        streams.add(Streams.RESULTS, mapOf(Streams.TYPE to type, Streams.CORRELATION_ID to "it", Streams.PAYLOAD to payload), 10_000)
    }

    private fun resultsPending() = redis.opsForStream<String, String>().pending(Streams.RESULTS, Streams.GROUP)?.totalPendingMessages

    private fun compliance(id: UUID) = repository.findByIdOrNull(id)?.compliance

    @Test
    fun `create publishes a LINT whose result sets compliance, ignoring stale results`() {
        val id = create()
        val lint = jobs(id, "LINT").single()
        assertEquals("println(1);", lint["content"].asText())

        answer("LINT", UUID.randomUUID().toString(), id, """{"violations":[]}""")
        answer("LINT", lint["jobId"].asText(), id, """{"violations":[{"rule":"r","message":"m","line":1,"column":2}]}""")

        waitFor { compliance(id) == Compliance.NOT_COMPLIANT }
        waitFor { resultsPending() == 0L }
        assertEquals(Compliance.NOT_COMPLIANT, compliance(id))
        assertEquals(
            2,
            repository
                .findByIdOrNull(id)!!
                .lintViolations
                .single()
                .column,
        )
    }

    @Test
    fun `saving lint rules sends snippets to pending and republishes, and FORMAT results are stored`() {
        val id = create()
        answer("LINT", jobs(id, "LINT").single()["jobId"].asText(), id, """{"violations":[]}""")
        waitFor { compliance(id) == Compliance.COMPLIANT }

        mockMvc
            .put("/rules/lint") {
                with(alice)
                contentType = MediaType.APPLICATION_JSON
                content = """[{"id":"identifier_format","name":"identifier_format","isActive":true,"value":"snake case"}]"""
            }.andExpect {
                status { isAccepted() }
                jsonPath("$[0].id") { value("identifier_format") }
            }
        assertEquals(Compliance.PENDING, compliance(id))
        assertEquals("snake case", jobs(id, "LINT").last()["rules"][0]["value"].asText())

        mockMvc.put("/snippets/$id") {
            with(alice)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"println(2);"}"""
        }
        val format = jobs(id, "FORMAT").single()
        answer("FORMAT", format["jobId"].asText(), id, """{"content":"println(2);\n"}""")
        waitFor { assets.stored[FORMATTED to id.toString()] == "println(2);\n" }
    }

    @Test
    fun `the stores of V2 and V3 work on Postgres`() {
        val owner = "auth0|${UUID.randomUUID()}"
        assertNull(rules.find(owner, RuleType.LINT))
        rules.save(owner, RuleType.LINT, listOf(RuleConfig("identifier_format", true, "camel case")))
        rules.save(owner, RuleType.LINT, listOf(RuleConfig("identifier_format", false, null), RuleConfig("n", true, 4)))
        assertEquals(listOf(RuleConfig("identifier_format", false, null), RuleConfig("n", true, 4)), rules.find(owner, RuleType.LINT))

        val id = create()
        val job = UUID.randomUUID()
        tracker.record(id, JobType.FORMAT, job)
        assertEquals(job, tracker.latest(id, JobType.FORMAT))
        mockMvc.delete("/snippets/$id") { with(alice) }.andExpect { status { isNoContent() } }
        // ON DELETE CASCADE
        assertNull(tracker.latest(id, JobType.FORMAT))

        // The app already stored the fake Runner's version at startup
        waitFor { parserVersions.current() == "2.0.0" }
        assertFalse(parserVersions.change("2.0.0"))
        assertTrue(parserVersions.change("3.0.0"))
        assertEquals("3.0.0", parserVersions.current())
        assertTrue(parserVersions.change("2.0.0"))
    }

    @Test
    fun `stream stats come from XLEN and XINFO GROUPS`() {
        val stream = "stats-${UUID.randomUUID()}"
        assertEquals(StreamStats(0, null, null), streams.stats(stream, "g"))
        repeat(3) { streams.add(stream, mapOf(Streams.TYPE to "LINT"), 10_000) }
        assertEquals(StreamStats(3, null, null), streams.stats(stream, "g"))

        // The group starts at `$`. Redis only knows its lag once it has caught up with the stream
        streams.createGroup(stream, "g")
        streams.add(stream, mapOf(Streams.TYPE to "LINT"), 10_000)
        streams.readNew(stream, "g", "c", 10, Duration.ofMillis(10))
        repeat(2) { streams.add(stream, mapOf(Streams.TYPE to "LINT"), 10_000) }

        assertEquals(StreamStats(6, 2, 1), streams.stats(stream, "g"))
    }

    @Test
    fun `the backlog gauges of both streams are registered`() {
        listOf(Streams.RESULTS to Streams.GROUP, Streams.JOBS to Streams.RUNNER_GROUP).forEach { (stream, group) ->
            assertNotNull(meters.find("redis.stream.pending").tags("stream", stream, "group", group).gauge())
        }
    }
}
