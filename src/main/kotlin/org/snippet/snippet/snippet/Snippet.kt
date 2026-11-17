package org.snippet.snippet.snippet

import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** Snippet metadata. The content is in the Asset Service, under the snippet id. */
@Entity
@Table(name = "snippet")
class Snippet(
    @Id
    val id: UUID,
    var name: String,
    var description: String?,
    var language: String,
    var version: String,
    var extension: String,
    @Column(name = "owner_id")
    val ownerId: String,
    @Column(name = "owner_name")
    val ownerName: String,
    @Convert(converter = ComplianceConverter::class)
    var compliance: Compliance = Compliance.PENDING,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lint_violations")
    var lintViolations: MutableList<StoredViolation> = mutableListOf(),
    @Column(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at")
    var updatedAt: Instant = createdAt,
)

/**
 * A lint violation as stored in `lint_violations`. Mutable with defaults so any
 * Jackson setup Hibernate uses can read it back.
 */
data class StoredViolation(
    var rule: String? = null,
    var message: String = "",
    var line: Int = 1,
    var column: Int = 1,
)

enum class Compliance(
    @get:JsonValue val value: String,
) {
    PENDING("pending"),
    FAILED("failed"),
    NOT_COMPLIANT("not-compliant"),
    COMPLIANT("compliant"),
    ;

    companion object {
        fun fromValue(value: String): Compliance =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Unknown compliance '$value'")
    }
}

/** Stores [Compliance] with its contract value (`not-compliant`), not the enum name. */
@Converter
class ComplianceConverter : AttributeConverter<Compliance, String> {
    override fun convertToDatabaseColumn(attribute: Compliance): String = attribute.value

    override fun convertToEntityAttribute(dbData: String): Compliance = Compliance.fromValue(dbData)
}
