package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.database.entity.effectiveStickerRefs
import cn.nabr.chatwithchat.data.richcontent.AssistantContentProjection

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

    val content = AssistantContentProjection.toMarkdown(effectiveContent().stripInternalStickerMarkers()).trim()
    if (marker.isBlank() || content.contains(marker)) return content

    return listOf(content, marker)
        .filter { value -> value.isNotBlank() }
        .joinToString(separator = "\n")
}

fun String.stripInternalStickerMarkers(): String {
    // The chat screen runs this on the main thread over the whole streamed answer and its thinking on
    // every publish. Most text matches none of the patterns, so rule that out with plain scans first.
    if (!needsStickerMarkerCleanup()) return trimEnd()
    return INTERNAL_STICKER_MARKER
        .replace(this, "")
        .let { value -> INCOMPLETE_INTERNAL_STICKER_MARKER.replace(value, "") }
        .replace(TRAILING_LINE_WHITESPACE, "\n")
        .replace(EXCESS_BLANK_LINES, "\n\n")
        .trimEnd()
}

/**
 * False only when no regex in [stripInternalStickerMarkers] can match. A replacement can create a new
 * match for a later pattern, but only after an earlier pattern matched, which already returns true here.
 */
private fun String.needsStickerMarkerCleanup(): Boolean {
    if ('\r' in this) return true
    if (contains(" \n") || contains("\t\n") || contains("\n\n\n")) return true
    var bracket = indexOf('[')
    while (bracket >= 0) {
        if (regionMatches(bracket, STICKER_MARKER_PREFIX, 0, STICKER_MARKER_PREFIX.length, ignoreCase = true)) {
            return true
        }
        bracket = indexOf('[', bracket + 1)
    }
    return false
}

private fun MessageStickerRef.boundedAltText(): String? = altText
    .replace(Regex("\\s+"), " ")
    .trim()
    .replace("\\", "\\\\")
    .replace("[", "\\[")
    .replace("]", "\\]")
    .take(MAX_STICKER_ALT_TEXT_CHARS)
    .takeIf { value -> value.isNotBlank() }

private const val STICKER_MARKER_PREFIX = "[assistant sent sticker:"
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
