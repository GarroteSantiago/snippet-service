package org.snippet.snippet.testcase

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.snippet.snippet.clients.runner.TestStatus
import java.time.Instant
import java.util.UUID

/** A saved test of a snippet (US-08). Deleted with the snippet by the foreign key. */
@Entity
@Table(name = "test_case")
class TestCase(
    @Id
    val id: UUID,
    @Column(name = "snippet_id")
    val snippetId: UUID,
    var name: String,
    @JdbcTypeCode(SqlTypes.JSON)
    var inputs: MutableList<String> = mutableListOf(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_outputs")
    var expectedOutputs: MutableList<String> = mutableListOf(),
    @Convert(converter = TestStatusConverter::class)
    @Column(name = "last_result")
    var lastResult: TestStatus? = null,
    @Column(name = "last_run_at")
    var lastRunAt: Instant? = null,
    // Latest TEST job published for this test (US-16); results of older jobs are ignored
    @Column(name = "last_job_id")
    var lastJobId: UUID? = null,
    @Column(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at")
    var updatedAt: Instant = createdAt,
)

/** Stores [TestStatus] with its contract value (`success`), not the enum name. */
@Converter
class TestStatusConverter : AttributeConverter<TestStatus, String> {
    override fun convertToDatabaseColumn(attribute: TestStatus?): String? = attribute?.value

    override fun convertToEntityAttribute(dbData: String?): TestStatus? = dbData?.let { TestStatus.fromValue(it) }
}
