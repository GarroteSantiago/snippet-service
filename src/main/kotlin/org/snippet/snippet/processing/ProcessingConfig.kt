package org.snippet.snippet.processing

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.snippet.snippet.clients.asset.AssetClient
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.common.http.BackgroundCalls
import org.snippet.snippet.common.logging.CorrelationIdFilter
import org.snippet.snippet.common.streams.RedisStreamClient
import org.snippet.snippet.common.streams.StreamClient
import org.snippet.snippet.common.streams.StreamMetrics
import org.snippet.snippet.common.streams.StreamMetricsLifecycle
import org.snippet.snippet.common.streams.StreamProperties
import org.snippet.snippet.common.streams.StreamWorker
import org.snippet.snippet.common.streams.StreamWorkerLifecycle
import org.snippet.snippet.common.streams.WatchedStream
import org.snippet.snippet.snippet.SnippetRepository
import org.snippet.snippet.testcase.TestCaseRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.util.UUID

/** The producer side is always there: requests publish jobs. */
@Configuration
@EnableConfigurationProperties(StreamProperties::class)
class StreamConfig {
    @Bean
    fun streamClient(redis: StringRedisTemplate): StreamClient = RedisStreamClient(redis)
}

/**
 * Background work, off with `streams.enabled=false`: the consumer of `snippet:results`
 * (group `snippet-service`, one consumer per hostname) and the `parserVersion` check.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty("streams.enabled", havingValue = "true", matchIfMissing = true)
class ProcessingConfig {
    @Bean
    fun resultWorker(
        client: StreamClient,
        properties: StreamProperties,
        repository: SnippetRepository,
        tracker: JobTracker,
        jobs: SnippetJobs,
        assets: AssetClient,
        background: BackgroundCalls,
        testCases: TestCaseRepository,
        json: ObjectMapper,
        meters: MeterRegistry,
    ) = StreamWorker(
        client = client,
        stream = Streams.RESULTS,
        group = Streams.GROUP,
        consumer = properties.consumer(),
        handler = ResultHandler(repository, tracker, jobs, assets, background, testCases, json),
        properties = properties,
        meters = meters,
    )

    @Bean
    fun resultWorkerLifecycle(
        worker: StreamWorker,
        properties: StreamProperties,
    ) = StreamWorkerLifecycle(worker, properties)

    /** Backlog of both streams: the jobs too, so it shows even while every Runner is down. */
    @Bean
    fun streamMetrics(
        client: StreamClient,
        meters: MeterRegistry,
    ) = StreamMetrics(
        client,
        listOf(WatchedStream(Streams.RESULTS, Streams.GROUP), WatchedStream(Streams.JOBS, Streams.RUNNER_GROUP)),
        meters,
    )

    @Bean
    fun streamMetricsLifecycle(
        metrics: StreamMetrics,
        @Value("\${observability.stream-metrics-interval:15s}") interval: Duration,
    ) = StreamMetricsLifecycle(metrics, interval)

    @Bean
    fun parserVersionWatcher(
        runner: RunnerClient,
        store: ParserVersionStore,
        jobs: SnippetJobs,
        background: BackgroundCalls,
    ) = ParserVersionWatcher(runner, store, jobs, background)
}

/**
 * US-18: on startup and every `parser-version.check-interval` (5 min), asks the Runner for its
 * `parserVersion`; when it changed, stores it and publishes a VALIDATE for every snippet. Only the
 * replica whose update wins in Postgres publishes them.
 */
class ParserVersionWatcher(
    private val runner: RunnerClient,
    private val store: ParserVersionStore,
    private val jobs: SnippetJobs,
    private val background: BackgroundCalls,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 0, fixedDelayString = "\${parser-version.check-interval:5m}")
    fun check() {
        // One correlation id per check, so its VALIDATE jobs and their results can be followed together
        MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString())
        try {
            val version =
                background.call { runner.versions() }.parserVersion
                    ?: return log.warn("The Runner doesn't report a parserVersion")
            if (!store.change(version)) return
            log.info("PrintScript parser is now {}: revalidating every snippet", version)
            background.call { jobs.validateAll() }
        } catch (e: Exception) {
            log.error("Checking the Runner's parserVersion failed; trying again later", e)
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY)
        }
    }
}
