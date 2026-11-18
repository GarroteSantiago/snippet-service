package org.snippet.snippet.processing

import org.junit.jupiter.api.Test
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.clients.runner.Versions
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.http.BackgroundCalls
import org.snippet.snippet.snippet.Snippet
import org.snippet.snippet.snippet.SnippetService.Companion.CONTENT
import org.snippet.snippet.support.InMemoryParserVersionStore
import org.snippet.snippet.support.ProcessingFixture
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserVersionWatcherTest {
    private val fixture = ProcessingFixture()
    private val store = InMemoryParserVersionStore()
    private val watcher = ParserVersionWatcher(fixture.runner, store, fixture.jobs, BackgroundCalls())

    private val snippets =
        List(2) {
            Snippet(UUID.randomUUID(), "s$it", null, "printscript", "1.1", "ps", "auth0|u$it", "u").also { s ->
                fixture.repository.save(s)
                fixture.assets.put(CONTENT, s.id.toString(), "println($it);")
            }
        }

    private fun validates() = fixture.publishedJobs().filter { it.first == "VALIDATE" }

    @Test
    fun `a new parser version is stored and every snippet is validated`() {
        watcher.check()

        assertEquals("2.0.0", store.version)
        assertEquals(snippets.map { it.id.toString() }.toSet(), validates().map { it.second["snippetId"].asText() }.toSet())
    }

    @Test
    fun `the same version publishes nothing and a different one revalidates again`() {
        watcher.check()
        watcher.check()
        assertEquals(2, validates().size)

        fixture.runner.versions = fixture.runner.versions.copy(parserVersion = "2.1.0")
        watcher.check()
        assertEquals("2.1.0", store.version)
        assertEquals(4, validates().size)
    }

    @Test
    fun `a runner that doesn't answer or doesn't report a version changes nothing`() {
        val down =
            object : RunnerClient by fixture.runner {
                override fun versions(): Versions = throw UpstreamException("PrintScript Runner", "answered 401")
            }
        ParserVersionWatcher(down, store, fixture.jobs, BackgroundCalls()).check()

        fixture.runner.versions = fixture.runner.versions.copy(parserVersion = null)
        watcher.check()

        assertEquals(null, store.version)
        assertTrue(validates().isEmpty())
    }
}
