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

    val content = effectiveContent().trim()
    if (marker.isBlank() || content.contains(marker)) return content

    return listOf(content, marker)
        .filter { value -> value.isNotBlank() }
        .joinToString(separator = "\n")
}

private fun MessageStickerRef.boundedAltText(): String? = altText
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(MAX_STICKER_ALT_TEXT_CHARS)
    .takeIf { value -> value.isNotBlank() }
