package org.snippet.snippet.language

import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.common.error.BadRequestException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** `FileType` in the public contract. */
data class FileType(
    val language: String,
    val extension: String,
    val versions: List<String>,
)

/**
 * File extension of each language. The Runner reports its language and versions
 * but not the extension, so it's configured here (`languages.extensions.*`).
 */
@ConfigurationProperties("languages")
data class LanguageProperties(
    val extensions: Map<String, String> = mapOf("printscript" to "ps"),
)

@Configuration
@EnableConfigurationProperties(LanguageProperties::class)
class LanguageConfig

/** What the Runner can run. Asked on each use, so a new Runner version shows up without restarting. */
@Service
class LanguageCatalog(
    private val runner: RunnerClient,
    private val properties: LanguageProperties,
) {
    fun all(): List<FileType> {
        val versions = runner.versions()
        // Falls back to the language name so an unconfigured language still gets a usable file name
        val extension = properties.extensions[versions.language] ?: versions.language
        return listOf(FileType(versions.language, extension, versions.versions))
    }

    /** Throws 400 unless [language], [version] and (if given) [extension] are supported together. */
    fun requireSupported(
        language: String,
        version: String,
        extension: String? = null,
    ) {
        val fileType =
            all().find { it.language == language }
                ?: throw BadRequestException("Unsupported language '$language'")
        if (version !in fileType.versions) {
            throw BadRequestException("Unsupported version '$version' for $language (supported: ${fileType.versions.joinToString()})")
        }
        if (extension != null && extension != fileType.extension) {
            throw BadRequestException("The extension for $language is '${fileType.extension}', not '$extension'")
        }
    }
}

@RestController
class LanguageController(
    private val catalog: LanguageCatalog,
) {
    @GetMapping("/languages")
    fun languages(): List<FileType> = catalog.all()
}
