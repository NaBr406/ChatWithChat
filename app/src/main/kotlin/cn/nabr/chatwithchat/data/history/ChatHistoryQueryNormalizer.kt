package cn.nabr.chatwithchat.data.history

import java.util.Locale

object ChatHistoryQueryNormalizer {
    private val whitespace = Regex("\\s+")

    fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(whitespace, " ")
        .trim()

    fun searchTerms(value: String): List<String> {
        val normalized = normalize(value)
        if (normalized.isBlank()) return emptyList()

        val terms = linkedSetOf<String>()
        normalized.split(' ').filter(String::isNotBlank).forEach { token ->
            if (token.any(::isCjk)) {
                val cjk = token.filter(::isCjk)
                cjk.forEach { char -> terms += char.toString() }
                cjk.windowed(size = 2).forEach(terms::add)
                cjk.windowed(size = 3).forEach(terms::add)
            } else {
                terms += token
            }
        }
        return terms.toList()
    }

    fun searchColumn(vararg values: String): String = values
        .flatMap(::searchTerms)
        .distinct()
        .joinToString(separator = " ")

    fun ftsMatchExpression(value: String): String {
        val terms = searchTerms(value).take(MAX_QUERY_TERMS)
        val separator = if (normalize(value).any(::isCjk)) " AND " else " OR "
        return terms.joinToString(separator = separator) { term ->
            "\"${term.replace("\"", "\"\"")}\""
        }
    }

    private fun isCjk(char: Char): Boolean = char.code in 0x3400..0x9FFF

    private const val MAX_QUERY_TERMS = 24
}
