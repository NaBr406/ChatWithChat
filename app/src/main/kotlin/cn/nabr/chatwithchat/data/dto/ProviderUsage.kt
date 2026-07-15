package cn.nabr.chatwithchat.data.dto

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.token.TokenUsageDetail
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
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
        label: String
    ): TokenUsageRecord? {
        val hasProviderUsage = promptTokens != null ||
            completionTokens != null ||
            inputTokens != null ||
            outputTokens != null ||
            totalTokens != null
        if (!hasProviderUsage) return null

        val input = inputTokens ?: promptTokens ?: 0
        val output = outputTokens ?: completionTokens ?: 0
        val total = totalTokens ?: (input + output)

        val detail = TokenUsageDetail(
            label = label,
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            isEstimated = false,
            isToolRelated = false
        )

        return TokenUsageRecord(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            isEstimated = false,
            provider = platform.name,
            platformUid = platform.uid,
            model = platform.model,
            details = listOf(detail)
        )
    }
}
