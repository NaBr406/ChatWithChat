package cn.nabr.chatwithchat.data.sticker

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object StickerMetadataCodec {
    private const val MAX_TITLE_LENGTH = 80
    private const val MAX_ALT_TEXT_LENGTH = 160
    private const val MAX_TAG_COUNT = 12
    private const val MAX_TAG_LENGTH = 32

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun normalize(metadata: StickerItemMetadata): StickerItemMetadata? {
        val title = sanitize(metadata.title, MAX_TITLE_LENGTH)
        val altText = sanitize(metadata.altText, MAX_ALT_TEXT_LENGTH)
        if (title.isBlank() || altText.isBlank()) return null

        val tags = sanitizeTerms(metadata.tags)
        val aliases = sanitizeTerms(metadata.aliases)

        return StickerItemMetadata(title = title, altText = altText, tags = tags, aliases = aliases)
    }

    fun encodeTags(tags: List<String>): String = json.encodeToString(tags)

    fun decodeTags(value: String): List<String> = try {
        sanitizeTerms(json.decodeFromString<List<String>>(value))
    } catch (_: SerializationException) {
        emptyList()
    }

    fun defaultMetadata(fileName: String): StickerItemMetadata {
        val title = sanitize(fileName.substringBeforeLast('.', fileName), MAX_TITLE_LENGTH)
            .ifBlank { "新表情" }
        return StickerItemMetadata(title = title, altText = title, tags = emptyList())
    }

    private fun sanitize(value: String, maxLength: Int): String = value
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(maxLength)

    private fun sanitizeTerms(values: List<String>): List<String> = values
        .map { value -> sanitize(value, MAX_TAG_LENGTH) }
        .filter { value -> value.isNotBlank() }
        .distinct()
        .take(MAX_TAG_COUNT)
}
