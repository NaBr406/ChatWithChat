package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.database.entity.isSafeLocalEntityId
import cn.nabr.chatwithchat.data.database.entity.safeHttpUrlOrNull
import kotlinx.serialization.encodeToString

data class ToolResultBounds(
    val maxContentChars: Int,
    val maxStructuredContentChars: Int = maxContentChars,
    val maxSourceCount: Int = 16,
    val maxSourcePayloadChars: Int = 1_024,
    val maxSourceTextChars: Int = 512,
    val maxMetadataChars: Int = maxContentChars,
    val maxTotalPayloadChars: Int = Int.MAX_VALUE
) {
    init {
        require(maxContentChars >= 0)
        require(maxStructuredContentChars >= 0)
        require(maxSourceCount >= 0)
        require(maxSourcePayloadChars >= 0)
        require(maxSourceTextChars >= 0)
        require(maxMetadataChars >= 0)
        require(maxTotalPayloadChars >= 0)
    }
}

enum class OversizedToolPayloadPolicy {
    DROP_OPTIONAL,
    REJECT_RESULT
}

enum class ToolResultPayloadPart {
    CONTENT,
    STRUCTURED_CONTENT,
    SOURCES,
    METADATA
}

data class BoundedToolResult(
    val result: ToolResult,
    val droppedPayloadParts: Set<ToolResultPayloadPart> = emptySet(),
    val isRejected: Boolean = false
)

fun ToolResult.boundPayload(
    bounds: ToolResultBounds,
    oversizedPayloadPolicy: OversizedToolPayloadPolicy = OversizedToolPayloadPolicy.DROP_OPTIONAL
): BoundedToolResult {
    val droppedParts = linkedSetOf<ToolResultPayloadPart>()
    var hasOversizedOptionalPayload = false
    var remainingChars = bounds.maxTotalPayloadChars

    val boundedMetadata = linkedMapOf<String, String>()
    var metadataPayloadChars = 0
    metadata[ERROR_CODE_METADATA_KEY]?.let { errorCode ->
        val candidate = mapOf(ERROR_CODE_METADATA_KEY to errorCode)
        val candidatePayloadChars = toolProtocolJson.encodeToString(candidate).length
        if (candidatePayloadChars <= bounds.maxMetadataChars && candidatePayloadChars <= remainingChars) {
            boundedMetadata[ERROR_CODE_METADATA_KEY] = errorCode
            metadataPayloadChars = candidatePayloadChars
            remainingChars -= candidatePayloadChars
        } else {
            droppedParts += ToolResultPayloadPart.METADATA
            hasOversizedOptionalPayload = true
        }
    }

    val contentLimit = minOf(bounds.maxContentChars, remainingChars)
    val boundedContent = content.clipSafely(contentLimit)
    if (boundedContent != content) droppedParts += ToolResultPayloadPart.CONTENT
    remainingChars -= boundedContent.length

    val boundedStructuredContent = structuredContent?.let { structured ->
        val serializedLength = toolProtocolJson.encodeToString(structured).length
        if (serializedLength <= bounds.maxStructuredContentChars && serializedLength <= remainingChars) {
            remainingChars -= serializedLength
            structured
        } else {
            droppedParts += ToolResultPayloadPart.STRUCTURED_CONTENT
            hasOversizedOptionalPayload = true
            null
        }
    }

    val boundedSources = mutableListOf<ToolSource>()
    val sourceBudget = minOf(remainingChars, bounds.maxSourcePayloadChars)
    sources.forEach { source ->
        val boundedSource = source.boundOrNull(bounds.maxSourceTextChars)
        when {
            boundedSource == null -> droppedParts += ToolResultPayloadPart.SOURCES
            boundedSources.size >= bounds.maxSourceCount -> {
                droppedParts += ToolResultPayloadPart.SOURCES
                hasOversizedOptionalPayload = true
            }
            toolProtocolJson.encodeToString(boundedSource).length > bounds.maxSourcePayloadChars -> {
                droppedParts += ToolResultPayloadPart.SOURCES
                hasOversizedOptionalPayload = true
            }
            else -> {
                val candidate = boundedSources + boundedSource
                if (toolProtocolJson.encodeToString(candidate).length <= sourceBudget) {
                    boundedSources += boundedSource
                } else {
                    droppedParts += ToolResultPayloadPart.SOURCES
                    hasOversizedOptionalPayload = true
                }
            }
        }
    }
    if (boundedSources.isNotEmpty()) {
        remainingChars -= toolProtocolJson.encodeToString(boundedSources).length
    }

    metadata.toSortedMap().forEach { (key, value) ->
        if (key == ERROR_CODE_METADATA_KEY) return@forEach
        val candidate = boundedMetadata + (key to value)
        val candidatePayloadChars = toolProtocolJson.encodeToString(candidate).length
        val addedPayloadChars = candidatePayloadChars - metadataPayloadChars
        if (candidatePayloadChars <= bounds.maxMetadataChars && addedPayloadChars <= remainingChars) {
            boundedMetadata[key] = value
            metadataPayloadChars = candidatePayloadChars
            remainingChars -= addedPayloadChars
        } else {
            droppedParts += ToolResultPayloadPart.METADATA
            hasOversizedOptionalPayload = true
        }
    }

    if (hasOversizedOptionalPayload && oversizedPayloadPolicy == OversizedToolPayloadPolicy.REJECT_RESULT) {
        val boundedRejection = ToolResult(
            callId = callId,
            name = name,
            content = "$TOOL_RESULT_TOO_LARGE:$name",
            isError = true,
            metadata = mapOf(ERROR_CODE_METADATA_KEY to TOOL_RESULT_TOO_LARGE)
        ).boundPayload(bounds)
        return BoundedToolResult(
            result = boundedRejection.result,
            droppedPayloadParts = droppedParts + boundedRejection.droppedPayloadParts,
            isRejected = true
        )
    }

    return BoundedToolResult(
        result = copy(
            content = boundedContent,
            metadata = boundedMetadata,
            structuredContent = boundedStructuredContent,
            sources = boundedSources
        ),
        droppedPayloadParts = droppedParts
    )
}

internal fun ToolResult.payloadCharCount(): Int {
    val optionalChars = listOfNotNull(
        structuredContent?.let { value -> toolProtocolJson.encodeToString(value).length },
        sources.takeIf { value -> value.isNotEmpty() }?.let { value -> toolProtocolJson.encodeToString(value).length },
        metadata.takeIf { value -> value.isNotEmpty() }?.let { value -> toolProtocolJson.encodeToString(value).length }
    ).sumOf { value -> value.toLong() }
    return (content.length.toLong() + optionalChars).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun ToolSource.boundOrNull(maxTextChars: Int): ToolSource? = when (this) {
    is ToolSource.PublicUrl -> url.safeHttpUrlOrNull()?.let { safeUrl ->
        ToolSource.PublicUrl(
            title = title.trim().ifBlank { safeUrl }.clipSafely(maxTextChars),
            url = safeUrl,
            snippet = snippet.trim().clipSafely(maxTextChars)
        )
    }
    is ToolSource.LocalApp ->
        localEntityId
            .trim()
            .takeIf { value -> value.isSafeLocalEntityId() }
            ?.let { safeEntityId ->
                ToolSource.LocalApp(
                    title = title.trim().ifBlank { safeEntityId }.clipSafely(maxTextChars),
                    localEntityId = safeEntityId,
                    navigationTarget = navigationTarget,
                    snippet = snippet.trim().clipSafely(maxTextChars)
                )
            }
}

private fun String.clipSafely(maxChars: Int): String {
    if (length <= maxChars) return this
    val clipped = take(maxChars)
    return if (clipped.lastOrNull()?.let { char -> Character.isHighSurrogate(char) } == true) {
        clipped.dropLast(1)
    } else {
        clipped
    }
}

private const val TOOL_RESULT_TOO_LARGE = "tool_result_too_large"
private const val ERROR_CODE_METADATA_KEY = "error_code"
