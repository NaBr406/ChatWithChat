package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.effectiveStickerRefs

private const val MAX_STICKER_ALT_TEXT_CHARS = 160
private const val MAX_STICKERS_IN_CONTEXT = 1

/**
 * Projects locally stored sticker state into bounded semantic context. Asset identifiers and
 * location data intentionally do not cross this boundary.
 */
fun MessageV2.semanticAssistantContent(): String {
    val marker = effectiveStickerRefs()
        .take(MAX_STICKERS_IN_CONTEXT)
        .mapNotNull(MessageStickerRef::boundedAltText)
        .joinToString(separator = " ") { altText -> "[assistant sent sticker: $altText]" }

    val content = effectiveContent().stripInternalStickerMarkers().trim()
    if (marker.isBlank() || content.contains(marker)) return content

    return listOf(content, marker)
        .filter { value -> value.isNotBlank() }
        .joinToString(separator = "\n")
}

fun String.stripInternalStickerMarkers(): String = INTERNAL_STICKER_MARKER
    .replace(this, "")
    .let { value -> INCOMPLETE_INTERNAL_STICKER_MARKER.replace(value, "") }
    .replace(TRAILING_LINE_WHITESPACE, "\n")
    .replace(EXCESS_BLANK_LINES, "\n\n")
    .trimEnd()

private fun MessageStickerRef.boundedAltText(): String? = altText
    .replace(Regex("\\s+"), " ")
    .trim()
    .replace("\\", "\\\\")
    .replace("[", "\\[")
    .replace("]", "\\]")
    .take(MAX_STICKER_ALT_TEXT_CHARS)
    .takeIf { value -> value.isNotBlank() }

private val INTERNAL_STICKER_MARKER = Regex(
    pattern = """\[assistant sent sticker:(?:\\[^\r\n]|[^\r\n\\])*?]""",
    option = RegexOption.IGNORE_CASE
)
private val INCOMPLETE_INTERNAL_STICKER_MARKER = Regex(
    pattern = """\[assistant sent sticker:(?:\\[^\r\n]|[^\r\n\\])*$""",
    option = RegexOption.IGNORE_CASE
)
private val TRAILING_LINE_WHITESPACE = Regex("[ \\t]+\\r?\\n")
private val EXCESS_BLANK_LINES = Regex("(?:\\r?\\n){3,}")
