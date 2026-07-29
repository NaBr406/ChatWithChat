package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class NativeToolResultProjection(
    val content: String,
    val metadata: Map<String, String>,
    val structuredContent: JsonElement?,
    val sources: List<ToolSource>
)

internal fun ToolResult.toNativeToolResultProjection(maxContentChars: Int): NativeToolResultProjection {
    val defaultProjection = NativeToolResultProjection(
        content = content.clip(maxContentChars),
        metadata = metadata,
        structuredContent = structuredContent,
        sources = sources
    )
    if (name != ToolDefinition.SearchStickers.name || isError) return defaultProjection

    val compactCandidates = structuredContent?.compactStickerCandidatesOrNull() ?: return defaultProjection
    return NativeToolResultProjection(
        content = STICKER_CANDIDATE_CONTENT.clip(maxContentChars),
        metadata = emptyMap(),
        structuredContent = compactCandidates,
        sources = emptyList()
    )
}

private fun JsonElement.compactStickerCandidatesOrNull(): JsonObject? {
    val candidates = (this as? JsonObject)?.get("candidates") as? JsonArray ?: return null
    return buildJsonObject {
        put(
            "candidates",
            JsonArray(
                candidates
                    .asSequence()
                    .mapNotNull { candidate -> candidate.compactStickerCandidateOrNull() }
                    .take(MAX_STICKER_CANDIDATES)
                    .toList()
            )
        )
    }
}

private fun JsonElement.compactStickerCandidateOrNull(): JsonObject? {
    val candidate = this as? JsonObject ?: return null
    val stickerId = candidate["sticker_id"].strictTextOrNull()
        ?.trim()
        ?.takeIf { value ->
            value.isNotBlank() &&
                value.length <= MAX_STICKER_ID_CHARS &&
                value.none(Char::isISOControl)
        }
        ?: return null
    val title = candidate["title"].boundedTextOrNull(MAX_TITLE_CHARS) ?: return null
    val altText = candidate["alt_text"].boundedTextOrNull(MAX_ALT_TEXT_CHARS) ?: return null
    val tags = (candidate["tags"] as? JsonArray)
        ?.asSequence()
        ?.mapNotNull { value -> value.boundedTextOrNull(MAX_TAG_CHARS) }
        ?.distinct()
        ?.take(MAX_TAGS_PER_CANDIDATE)
        ?.map(::JsonPrimitive)
        ?.toList()
        .orEmpty()

    return buildJsonObject {
        put("sticker_id", stickerId)
        put("title", title)
        put("alt_text", altText)
        put("tags", JsonArray(tags))
    }
}

private fun JsonElement?.boundedTextOrNull(maxChars: Int): String? = strictTextOrNull()
    ?.replace(WHITESPACE, " ")
    ?.trim()
    ?.take(maxChars)
    ?.takeIf(String::isNotBlank)

private fun JsonElement?.strictTextOrNull(): String? = (this as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.contentOrNull

private fun String.clip(maxChars: Int): String {
    val boundedMax = maxChars.coerceAtLeast(0)
    if (length <= boundedMax) return this
    return take(boundedMax).trimEnd()
}

private const val STICKER_CANDIDATE_CONTENT = "贴图候选见 structured_content。"
private const val MAX_STICKER_CANDIDATES = 6
private const val MAX_STICKER_ID_CHARS = 160
private const val MAX_TITLE_CHARS = 80
private const val MAX_ALT_TEXT_CHARS = 160
private const val MAX_TAG_CHARS = 32
private const val MAX_TAGS_PER_CANDIDATE = 8
private val WHITESPACE = Regex("\\s+")
