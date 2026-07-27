package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.sticker.StickerRepository
import cn.nabr.chatwithchat.data.sticker.StickerSearchCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class SearchStickersToolProvider(
    private val stickerRepository: StickerRepository
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.SearchStickers

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        userVisible = false,
        category = ToolCategory.Other,
        defaultEnabled = true,
        isSensitive = false,
        presentationKey = "automatic_sticker_replies",
        iconKey = "stickers",
        enablementGroup = ToolEnablementGroup.AutomaticStickerReplies
    )

    override val discoveryMetadata: ToolDiscoveryMetadata = ToolDiscoveryMetadata(
        exposure = ToolExposure.Resident,
        intentTags = setOf("sticker", "emoji", "reaction", "表情", "表情包", "反应", "情绪"),
        requiredCompanionToolNames = setOf(ToolDefinition.SendSticker.name),
        priority = 100
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPrivate
    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 1,
        maxCallsPerChat = MAX_STICKER_SEARCH_CALLS_PER_REQUEST,
        timeoutSeconds = 2,
        maxResultChars = MAX_SEARCH_RESULT_CHARS,
        maxCallsPerRequestErrorKey = "max_sticker_searches_per_request",
        maxCallsPerChatErrorKey = "max_sticker_searches_per_request"
    )

    override fun progressLabel(call: ToolCall): String = STICKER_PROGRESS_LABEL

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
        val request = call.parseSearchRequest().getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}")
        }
        val candidates = runCatching {
            stickerRepository.ensureInitialized()
            stickerRepository.searchEnabledStatic(request.query, request.limit)
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            return call.errorResult(STICKER_UNAVAILABLE, STICKER_UNAVAILABLE)
        }.asSequence()
            .mapNotNull(::boundedCandidateOrNull)
            .take(request.limit)
            .toList()

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = candidates.toFallbackContent(),
            metadata = mapOf("candidate_count" to candidates.size.toString()),
            structuredContent = candidates.toStructuredContent()
        )
    }

    override fun onSessionResult(result: ToolResult, sessionState: ToolExecutionSessionState) {
        if (result.isError) return
        sessionState.replaceValues(STICKER_CANDIDATE_IDS_SESSION_KEY, result.visibleCandidateIds())
    }

    private fun ToolCall.parseSearchRequest(): Result<StickerSearchRequest> = runCatching {
        val arguments = argumentsObject().getOrThrow()
        val query = arguments["query"]
            ?.let { value -> value as? JsonPrimitive }
            ?.takeIf { value -> value.isString }
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        require(query.isNotBlank()) { "query_required" }
        require(query.length <= MAX_QUERY_CHARS) { "query_too_long" }

        val limit = arguments["limit"]?.let { value ->
            val primitive = value as? JsonPrimitive
                ?: throw IllegalArgumentException("limit_integer_expected")
            primitive.intOrNull ?: throw IllegalArgumentException("limit_integer_expected")
        } ?: DEFAULT_STICKER_CANDIDATES
        require(limit in 1..MAX_STICKER_CANDIDATES) { "limit_out_of_range" }

        StickerSearchRequest(query = query, limit = limit)
    }

    private fun boundedCandidateOrNull(candidate: StickerSearchCandidate): StickerSearchCandidate? {
        val stickerId = candidate.stickerId.trim()
        if (stickerId.isBlank() || stickerId.length > MAX_STICKER_ID_CHARS || stickerId.any(Char::isISOControl)) {
            return null
        }
        val title = candidate.title.normalizeForToolResult(MAX_TITLE_CHARS)
        val altText = candidate.altText.normalizeForToolResult(MAX_ALT_TEXT_CHARS)
        if (title.isBlank() || altText.isBlank()) return null

        val tags = candidate.tags.asSequence()
            .map { tag -> tag.normalizeForToolResult(MAX_TAG_CHARS) }
            .filter { tag -> tag.isNotBlank() }
            .distinct()
            .take(MAX_TAGS_PER_CANDIDATE)
            .toList()
        return StickerSearchCandidate(
            stickerId = stickerId,
            title = title,
            altText = altText,
            tags = tags
        )
    }
}

private data class StickerSearchRequest(
    val query: String,
    val limit: Int
)

private fun List<StickerSearchCandidate>.toFallbackContent(): String = when {
    isEmpty() -> "No sticker candidates found."
    else -> buildString {
        appendLine("Sticker candidates:")
        append(
            joinToString(separator = "\n") { candidate ->
                buildString {
                    append("sticker_id=")
                    append(candidate.stickerId)
                    append("; title=")
                    append(candidate.title)
                    append("; alt_text=")
                    append(candidate.altText)
                    candidate.tags.takeIf { tags -> tags.isNotEmpty() }?.let { tags ->
                        append("; tags=")
                        append(tags.joinToString(separator = ","))
                    }
                }
            }
        )
    }
}

private fun List<StickerSearchCandidate>.toStructuredContent() = buildJsonObject {
    put(
        "candidates",
        JsonArray(
            map { candidate ->
                buildJsonObject {
                    put("sticker_id", JsonPrimitive(candidate.stickerId))
                    put("title", JsonPrimitive(candidate.title))
                    put("alt_text", JsonPrimitive(candidate.altText))
                    put("tags", JsonArray(candidate.tags.map(::JsonPrimitive)))
                }
            }
        )
    )
}

private fun String.normalizeForToolResult(maxChars: Int): String = replace(Regex("\\s+"), " ")
    .trim()
    .take(maxChars)

private fun ToolResult.visibleCandidateIds(): Set<String> {
    val fallbackIds = FALLBACK_CANDIDATE_ID.findAll(content)
        .map { match -> match.groupValues[1].trim() }
    val structuredIds = runCatching {
        structuredContent
            ?.jsonObject
            ?.get("candidates")
            ?.jsonArray
            ?.mapNotNull { candidate ->
                candidate.jsonObject["sticker_id"]
                    ?.let { value -> value as? JsonPrimitive }
                    ?.takeIf { value -> value.isString }
                    ?.contentOrNull
                    ?.trim()
            }
            .orEmpty()
            .asSequence()
    }.getOrElse { emptySequence() }

    return (fallbackIds + structuredIds)
        .filter { stickerId ->
            stickerId.isNotBlank() &&
                stickerId.length <= MAX_STICKER_ID_CHARS &&
                stickerId.none(Char::isISOControl)
        }
        .take(MAX_STICKER_CANDIDATES)
        .toSet()
}

internal const val STICKER_PROGRESS_LABEL = "正在挑选表情"
internal const val STICKER_UNAVAILABLE = "sticker_unavailable"
internal const val STICKER_CANDIDATE_IDS_SESSION_KEY = "sticker_search_candidates"
internal const val MAX_STICKER_SEARCH_CALLS_PER_REQUEST = 2
private const val MAX_QUERY_CHARS = 120
private const val MAX_STICKER_CANDIDATES = 6
private const val DEFAULT_STICKER_CANDIDATES = 3
private const val MAX_STICKER_ID_CHARS = 160
private const val MAX_TITLE_CHARS = 80
private const val MAX_ALT_TEXT_CHARS = 160
private const val MAX_TAG_CHARS = 32
private const val MAX_TAGS_PER_CANDIDATE = 8
private const val MAX_SEARCH_RESULT_CHARS = 2_400
private val FALLBACK_CANDIDATE_ID = Regex("(?:^|\\n)sticker_id=([^;\\r\\n]+)")
