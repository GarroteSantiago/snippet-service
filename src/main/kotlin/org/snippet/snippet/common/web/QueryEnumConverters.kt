package org.snippet.snippet.common.web

import org.snippet.snippet.clients.permission.RelationFilter
import org.snippet.snippet.snippet.Compliance
import org.snippet.snippet.snippet.SortField
import org.snippet.snippet.snippet.SortOrder
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.format.FormatterRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Query parameters use the contract's values (`not-compliant`, `asc`, ...), not the
 * enum names. An unknown value fails the conversion, which Spring answers with 400.
 */
@Configuration
class QueryEnumConverters : WebMvcConfigurer {
    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverter(byValue(Compliance.entries) { it.value })
        registry.addConverter(byValue(RelationFilter.entries) { it.value })
        registry.addConverter(byValue(SortField.entries) { it.value })
        registry.addConverter(byValue(SortOrder.entries) { it.value })
    }

    private inline fun <reified E : Enum<E>> byValue(
        entries: List<E>,
        crossinline value: (E) -> String,
    ): Converter<String, E> =
        object : Converter<String, E> {
            override fun convert(source: String): E =
                entries.find { value(it) == source }
                    ?: throw IllegalArgumentException("'$source' is not one of ${entries.joinToString { value(it) }}")
        }
}
