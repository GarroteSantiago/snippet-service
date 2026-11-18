package org.snippet.snippet.processing

import org.junit.jupiter.api.Test
import org.snippet.snippet.clients.runner.Diagnostic
import org.snippet.snippet.common.http.BackgroundCalls
import org.snippet.snippet.common.streams.StreamProperties
import org.snippet.snippet.common.streams.StreamWorker
import org.snippet.snippet.snippet.Compliance
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetService.Companion.CONTENT
import org.snippet.snippet.snippet.SnippetService.Companion.FORMATTED
import org.snippet.snippet.snippet.StoredViolation
import org.snippet.snippet.support.ProcessingFixture
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultHandlerTest {
    /** Stands in for the future M2M token: counts the calls made without a user. */
    private class RecordingBackgroundCalls : BackgroundCalls() {
        var calls = 0

        override fun <T> call(block: () -> T): T {
            calls++
            return block()
        }
    }

    private val fixture = ProcessingFixture()
    private val background = RecordingBackgroundCalls()
    private val handler =
        ResultHandler(fixture.repository, fixture.tracker, fixture.jobs, fixture.assets, background, fixture.json)
    private val worker =
        StreamWorker(fixture.streams, Streams.RESULTS, Streams.GROUP, "snippet-1", handler, StreamProperties()).apply { createGroup() }

    private val snippet =
        Snippet(UUID.randomUUID(), "hello", null, "printscript", "1.1", "ps", "auth0|alice", "Alice").also {
            fixture.repository.save(it)
            fixture.assets.put(CONTENT, it.id.toString(), "println(1);")
        }

    /** Records [jobId] as the latest [type] job of the snippet, as publishing would. */
    private fun published(
        type: JobType,
        jobId: UUID = UUID.randomUUID(),
    ) = jobId.also { fixture.tracker.record(snippet.id, type, it) }

    private fun send(
        type: JobType,
        jobId: UUID,
        result: String?,
        status: String = "OK",
        error: String? = null,
    ) {
        val payload =
            buildString {
                append("""{"jobId":"$jobId","snippetId":"${snippet.id}","status":"$status"""")
                error?.let { append(""","error":"$it"""") }
                result?.let { append(""","result":$it""") }
                append("}")
            }
        fixture.streams.add(
            Streams.RESULTS,
            mapOf(Streams.TYPE to type.name, Streams.CORRELATION_ID to "corr-1", Streams.PAYLOAD to payload),
            10_000,
        )
    }

    private fun stored() = fixture.repository.snippets.getValue(snippet.id)

    private fun assertAllAcked() = assertTrue(fixture.streams.pending(Streams.RESULTS, Streams.GROUP).isEmpty())

    @Test
    fun `lint without violations is compliant`() {
        send(JobType.LINT, published(JobType.LINT), """{"violations":[]}""")
        worker.pollOnce()

        assertEquals(Compliance.COMPLIANT, stored().compliance)
        assertAllAcked()
    }

    @Test
    fun `lint with violations is not compliant and keeps them`() {
        send(
            JobType.LINT,
            published(JobType.LINT),
            """{"violations":[{"rule":"identifier_format","message":"Use camel case","line":1,"column":5}]}""",
        )
        worker.pollOnce()

        assertEquals(Compliance.NOT_COMPLIANT, stored().compliance)
        assertEquals(listOf(StoredViolation("identifier_format", "Use camel case", 1, 5)), stored().lintViolations)
    }

    @Test
    fun `a failed lint leaves the snippet failed`() {
        send(JobType.LINT, published(JobType.LINT), result = null, status = "FAILED", error = "boom")
        worker.pollOnce()

        assertEquals(Compliance.FAILED, stored().compliance)
    }

    @Test
    fun `a result of an older job is ignored and acked`() {
        val old = published(JobType.LINT)
        published(JobType.LINT)
        send(JobType.LINT, old, """{"violations":[{"rule":"x","message":"m","line":1,"column":1}]}""")
        worker.pollOnce()

        assertEquals(Compliance.PENDING, stored().compliance)
        assertAllAcked()
    }

    @Test
    fun `a result for a deleted snippet is ignored and acked`() {
        send(JobType.LINT, published(JobType.LINT), """{"violations":[]}""")
        fixture.repository.snippets.clear()
        worker.pollOnce()

        assertAllAcked()
    }

    @Test
    fun `format stores the formatted content, without a user`() {
        send(JobType.FORMAT, published(JobType.FORMAT), """{"content":"println(1);\n"}""")
        worker.pollOnce()

        assertEquals("println(1);\n", fixture.assets.stored[FORMATTED to snippet.id.toString()])
        assertEquals(1, background.calls)
        assertEquals(Compliance.PENDING, stored().compliance)
    }

    @Test
    fun `a failed format only logs`() {
        send(JobType.FORMAT, published(JobType.FORMAT), result = null, status = "FAILED", error = "no parse")
        worker.pollOnce()

        assertNull(fixture.assets.stored[FORMATTED to snippet.id.toString()])
        assertEquals(Compliance.PENDING, stored().compliance)
        assertAllAcked()
    }

    @Test
    fun `a snippet still valid with the new parser is linted again`() {
        val validate = published(JobType.VALIDATE)
        send(JobType.VALIDATE, validate, """{"valid":true,"diagnostics":[]}""")
        worker.pollOnce()

        val lint = fixture.jobsOf(snippet.id, "LINT").single()
        assertEquals("println(1);", lint["content"].asText())
        assertEquals("identifier_format", lint["rules"][0]["name"].asText())
        assertEquals(UUID.fromString(lint["jobId"].asText()), fixture.tracker.latest(snippet.id, JobType.LINT))
        // A repeated VALIDATE result is stale now
        assertNull(fixture.tracker.latest(snippet.id, JobType.VALIDATE))
        assertTrue(background.calls > 0)

        send(JobType.VALIDATE, validate, """{"valid":true,"diagnostics":[]}""")
        worker.pollOnce()
        assertEquals(1, fixture.jobsOf(snippet.id, "LINT").size)
    }

    @Test
    fun `a snippet invalid with the new parser is kept, not compliant, with the diagnostics`() {
        send(
            JobType.VALIDATE,
            published(JobType.VALIDATE),
            """{"valid":false,"diagnostics":[{"rule":null,"message":"Unexpected ';'","line":2,"column":3}]}""",
        )
        worker.pollOnce()

        assertEquals(Compliance.NOT_COMPLIANT, stored().compliance)
        assertEquals(listOf(StoredViolation(null, "Unexpected ';'", 2, 3)), stored().lintViolations)
        assertTrue(fixture.repository.snippets.containsKey(snippet.id))
        assertTrue(fixture.jobsOf(snippet.id, "LINT").isEmpty())
    }

    @Test
    fun `a failed validate leaves the snippet failed`() {
        send(JobType.VALIDATE, published(JobType.VALIDATE), result = null, status = "FAILED", error = "boom")
        worker.pollOnce()

        assertEquals(Compliance.FAILED, stored().compliance)
    }

    @Test
    fun `a result that keeps failing is discarded after more than three deliveries, leaving lint failed`() {
        send(JobType.LINT, published(JobType.LINT), """{"violations":[]}""")
        fixture.repository.failSave = true
        worker.pollOnce()
        repeat(2) {
            fixture.streams.advance(Duration.ofSeconds(61))
            worker.reclaimOnce()
        }
        assertEquals(1, fixture.streams.pending(Streams.RESULTS, Streams.GROUP).size)

        fixture.repository.failSave = false
        fixture.streams.advance(Duration.ofSeconds(61))
        worker.reclaimOnce()

        assertEquals(Compliance.FAILED, stored().compliance)
        assertAllAcked()
    }

    @Test
    fun `a discarded format result changes nothing`() {
        send(JobType.FORMAT, published(JobType.FORMAT), """{"content":"x"}""")
        fixture.assets.failPut = true
        worker.pollOnce()
        repeat(3) {
            fixture.streams.advance(Duration.ofSeconds(61))
            worker.reclaimOnce()
        }

        assertEquals(Compliance.PENDING, stored().compliance)
        assertAllAcked()
    }

    @Test
    fun `test results are acked and left for stage 7, and malformed results are dropped`() {
        send(JobType.TEST, UUID.randomUUID(), """{"testId":"t","status":"success","outputs":[]}""")
        fixture.streams.add(Streams.RESULTS, mapOf(Streams.TYPE to "LINT", Streams.PAYLOAD to "{oops"), 10_000)
        worker.pollOnce()

        assertEquals(Compliance.PENDING, stored().compliance)
        assertAllAcked()
    }

    @Test
    fun `diagnostics keep their position`() {
        val diagnostic = Diagnostic("r", "m", 3, 4)
        send(JobType.LINT, published(JobType.LINT), fixture.json.writeValueAsString(mapOf("violations" to listOf(diagnostic))))
        worker.pollOnce()

        assertEquals(listOf(StoredViolation("r", "m", 3, 4)), stored().lintViolations)
    }
}
