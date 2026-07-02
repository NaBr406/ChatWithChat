package dev.chungjungsoo.gptmobile.data.token

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsageRecord(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = inputTokens + outputTokens,
    val toolInputTokens: Int = 0,
    val toolOutputTokens: Int = 0,
    val toolTotalTokens: Int = toolInputTokens + toolOutputTokens,
    val isEstimated: Boolean = true,
    val provider: String = "",
    val platformUid: String = "",
    val model: String = "",
    val turnIndex: Int? = null,
    val messageId: Int? = null,
    val platformIndex: Int? = null,
    val details: List<TokenUsageDetail> = emptyList()
) {
    val hasTokens: Boolean
        get() = totalTokens > 0 || toolTotalTokens > 0

    fun withBinding(
        turnIndex: Int,
        platformIndex: Int,
        messageId: Int
    ): TokenUsageRecord = copy(
        turnIndex = turnIndex,
        platformIndex = platformIndex,
        messageId = messageId.takeIf { it > 0 }
    )
}

@Serializable
data class TokenUsageDetail(
    val label: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = inputTokens + outputTokens,
    val isEstimated: Boolean = true,
    val isToolRelated: Boolean = false
)

fun List<TokenUsageRecord>.sumTokenUsage(
    provider: String,
    platformUid: String,
    model: String,
    label: String = "合计"
): TokenUsageRecord {
    val inputTokens = sumOf { it.inputTokens }
    val outputTokens = sumOf { it.outputTokens }
    val toolInputTokens = sumOf { it.toolInputTokens }
    val toolOutputTokens = sumOf { it.toolOutputTokens }
    val details = flatMap { usage ->
        usage.details.ifEmpty {
            listOf(
                TokenUsageDetail(
                    label = label,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    totalTokens = usage.totalTokens,
                    isEstimated = usage.isEstimated,
                    isToolRelated = usage.toolTotalTokens > 0
                )
            )
        }
    }

    return TokenUsageRecord(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = inputTokens + outputTokens,
        toolInputTokens = toolInputTokens,
        toolOutputTokens = toolOutputTokens,
        toolTotalTokens = toolInputTokens + toolOutputTokens,
        isEstimated = any { it.isEstimated },
        provider = provider,
        platformUid = platformUid,
        model = model,
        details = details
    )
}
