package cn.nabr.chatwithchat.data.dto

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.token.TokenUsageDetail
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import cn.nabr.chatwithchat.data.token.TokenCacheStatus
import cn.nabr.chatwithchat.data.token.hasInvalidCacheValues
import cn.nabr.chatwithchat.data.token.normalizedCacheValues
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
    val totalTokens: Int? = null,

    @SerialName("prompt_cache_hit_tokens")
    val promptCacheHitTokens: Int? = null,

    @SerialName("prompt_cache_miss_tokens")
    val promptCacheMissTokens: Int? = null,

    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Int? = null,

    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Int? = null,

    @SerialName("cachedContentTokenCount")
    val cachedContentTokenCount: Int? = null,

    @SerialName("prompt_tokens_details")
    val promptTokensDetails: PromptTokensDetails? = null,

    @SerialName("input_tokens_details")
    val inputTokensDetails: InputTokensDetails? = null
) {
    fun toTokenUsageRecord(
        platform: PlatformV2,
        label: String
    ): TokenUsageRecord? {
        val hasProviderUsage = promptTokens != null ||
            completionTokens != null ||
            inputTokens != null ||
            outputTokens != null ||
            totalTokens != null ||
            promptCacheHitTokens != null ||
            promptCacheMissTokens != null ||
            cacheReadInputTokens != null ||
            cacheCreationInputTokens != null ||
            cachedContentTokenCount != null ||
            promptTokensDetails != null ||
            inputTokensDetails != null
        if (!hasProviderUsage) return null

        val input = inputTokens ?: promptTokens ?: 0
        val output = outputTokens ?: completionTokens ?: 0
        val total = totalTokens ?: (input + output)
        val nestedCacheHit = inputTokensDetails?.cachedTokens ?: promptTokensDetails?.cachedTokens
        val nestedCacheWrite = inputTokensDetails?.cacheWriteTokens ?: promptTokensDetails?.cacheWriteTokens
        val explicitHit = promptCacheHitTokens ?: cacheReadInputTokens
            ?: nestedCacheHit ?: cachedContentTokenCount
        val explicitMiss = promptCacheMissTokens
            ?: cacheReadInputTokens?.let {
                input + (cacheCreationInputTokens ?: 0)
            }
            ?: nestedCacheHit?.let { cached -> input - cached }
            ?: cachedContentTokenCount?.let { cached -> input - cached }
        val cacheValues = normalizedCacheValues(
            inputTokens = input,
            hitTokens = explicitHit,
            missTokens = explicitMiss,
            writeTokens = nestedCacheWrite ?: cacheCreationInputTokens,
            allowInputOverflow = cacheReadInputTokens != null
        )
        val hasInvalidCache = hasInvalidCacheValues(
            input,
            explicitHit,
            explicitMiss,
            allowInputOverflow = cacheReadInputTokens != null
        )
        val cacheStatus = when {
            hasInvalidCache -> TokenCacheStatus.UNAVAILABLE
            cacheValues != null -> TokenCacheStatus.KNOWN
            explicitHit != null || explicitMiss != null || nestedCacheWrite != null ||
                cacheCreationInputTokens != null -> TokenCacheStatus.PARTIAL
            else -> TokenCacheStatus.UNAVAILABLE
        }

        val detail = TokenUsageDetail(
            label = label,
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            isEstimated = false,
            isToolRelated = false,
            cacheHitTokens = cacheValues?.hitTokens,
            cacheMissTokens = cacheValues?.missTokens,
            cacheWriteTokens = cacheValues?.writeTokens,
            cacheStatus = cacheStatus
        )

        return TokenUsageRecord(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            isEstimated = false,
            provider = platform.name,
            platformUid = platform.uid,
            model = platform.model,
            details = listOf(detail),
            cacheHitTokens = cacheValues?.hitTokens,
            cacheMissTokens = cacheValues?.missTokens,
            cacheWriteTokens = cacheValues?.writeTokens,
            cacheStatus = cacheStatus
        )
    }
}

@Serializable
data class PromptTokensDetails(
    @SerialName("cached_tokens")
    val cachedTokens: Int? = null,

    @SerialName("cache_write_tokens")
    val cacheWriteTokens: Int? = null
)

@Serializable
data class InputTokensDetails(
    @SerialName("cached_tokens")
    val cachedTokens: Int? = null,

    @SerialName("cache_write_tokens")
    val cacheWriteTokens: Int? = null
)
