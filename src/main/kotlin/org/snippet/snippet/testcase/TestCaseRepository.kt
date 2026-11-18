package org.snippet.snippet.testcase

import org.snippet.snippet.clients.runner.TestStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface TestCaseRepository : JpaRepository<TestCase, UUID> {
    /** In creation order, so the list doesn't reorder when a test is edited or run. */
    fun findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId: UUID): List<TestCase>

    fun findByIdAndSnippetId(
        id: UUID,
        snippetId: UUID,
    ): TestCase?

    /**
     * Sets only the result columns, so a result that arrives while the owner edits
     * the test doesn't overwrite the edit. Returns how many rows changed (0 if the test is gone).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TestCase t set t.lastResult = :result, t.lastRunAt = :runAt where t.id = :id")
    fun updateResult(
        id: UUID,
        result: TestStatus,
        runAt: Instant,
    ): Int

    /** Remembers [jobId] as the latest TEST job of the test, before it is published. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TestCase t set t.lastJobId = :jobId where t.id = :id")
    fun updateLastJob(
        id: UUID,
        jobId: UUID,
    ): Int

    /**
     * Like [updateResult], but only if [jobId] is still the latest TEST job of the test, so a
     * result of an older run can't overwrite a newer one. 0 if it isn't (or the test is gone).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "update TestCase t set t.lastResult = :result, t.lastRunAt = :runAt where t.id = :id and t.lastJobId = :jobId",
    )
    fun updateResultOfJob(
        id: UUID,
        jobId: UUID,
        result: TestStatus,
        runAt: Instant,
    ): Int
}
