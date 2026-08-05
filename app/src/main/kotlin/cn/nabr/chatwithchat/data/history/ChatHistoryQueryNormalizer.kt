package cn.nabr.chatwithchat.data.history

import java.text.Normalizer
import java.util.Locale

data class HistoryQueryTerms(
    val normalizedText: String,
    val tokens: List<String>,
    val matchQuery: String
)

object ChatHistoryQueryNormalizer {
    fun normalize(text: String): HistoryQueryTerms {
        val normalized = normalizeText(text)
        val tokens = indexTerms(normalized)
        return HistoryQueryTerms(
            normalizedText = normalized,
            tokens = tokens,
            matchQuery = tokens.joinToString(separator = " OR ") { token -> "\"$token\"" }
        )
    }

    fun indexTerms(text: String): List<String> {
        val normalized = normalizeText(text)
        val tokens = LinkedHashSet<String>()
        val latinToken = StringBuilder()
        val cjkRun = StringBuilder()

        fun flushLatin() {
            if (latinToken.isNotEmpty()) {
                tokens += latinToken.toString()
                latinToken.clear()
            }
        }

        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            val codePoints = cjkRun.toString().codePoints().toArray()
            codePoints.forEach { codePoint -> tokens += cjkToken(codePoint.toString(16)) }
            listOf(2, 3).forEach { size ->
                if (codePoints.size < size) return@forEach
                codePoints.indices
                    .take(codePoints.size - size + 1)
                    .forEach { start ->
                        val value = buildString {
                            repeat(size) { offset ->
                                append(codePoints[start + offset].toString(16))
                                if (offset != size - 1) append('_')
                            }
                        }
                        tokens += cjkToken(value)
                    }
            }
            cjkRun.clear()
        }

        normalized.codePoints().forEach { codePoint ->
            when {
                isHan(codePoint) -> {
                    flushLatin()
                    cjkRun.appendCodePoint(codePoint)
                }
                Character.isLetterOrDigit(codePoint) || codePoint == '_'.code -> {
                    flushCjk()
                    latinToken.appendCodePoint(codePoint)
                }
                else -> {
                    flushCjk()
                    flushLatin()
                }
            }
        }
        flushCjk()
        flushLatin()
        return tokens.take(MAX_INDEX_TERMS)
    }

    fun normalizeText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(Locale.ROOT)

    private fun cjkToken(value: String): String = "cjk_$value"

    private fun isHan(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF

    private const val MAX_INDEX_TERMS = 20_000
}
