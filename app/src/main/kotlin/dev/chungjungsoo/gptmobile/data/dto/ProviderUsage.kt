package dev.chungjungsoo.gptmobile.data.dto

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.token.TokenUsageDetail
import dev.chungjungsoo.gptmobile.data.token.TokenUsageRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProviderUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,

    @SerialName("completion_tokens")
    val completionTokens: Int? = null,

    @SerialName("input_tokens")
    val inputTokens: Int? = null,

    @SerialName("output_tokens")
    val outputTokens: Int? = null,

    @SerialName("total_tokens")
    val totalTokens: Int? = null
) {
    fun toTokenUsageRecord(
        platform: PlatformV2,
        label: String,
        isToolRelated: Boolean
    ): TokenUsageRecord? {
        val input = inputTokens ?: promptTokens ?: 0
        val output = outputTokens ?: completionTokens ?: 0
        val total = totalTokens ?: (input + output)
        if (input <= 0 && output <= 0 && total <= 0) return null

        val detail = TokenUsageDetail(
            label = label,
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            isEstimated = false,
            isToolRelated = isToolRelated
        )

        return TokenUsageRecord(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            toolInputTokens = if (isToolRelated) input else 0,
            toolOutputTokens = if (isToolRelated) output else 0,
            toolTotalTokens = if (isToolRelated) total else 0,
            isEstimated = false,
            provider = platform.name,
            platformUid = platform.uid,
            model = platform.model,
            details = listOf(detail)
        )
    }
}
