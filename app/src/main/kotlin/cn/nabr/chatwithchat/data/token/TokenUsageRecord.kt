package cn.nabr.chatwithchat.data.token

import kotlinx.serialization.Serializable

@Serializable
enum class TokenCacheStatus {
    KNOWN,
    PARTIAL,
    UNAVAILABLE
}

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
    val details: List<TokenUsageDetail> = emptyList(),
    val cacheHitTokens: Int? = null,
    val cacheMissTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val cacheStatus: TokenCacheStatus = TokenCacheStatus.UNAVAILABLE
) {
    val hasTokens: Boolean
        get() = totalTokens > 0 || toolTotalTokens > 0

    val cacheInputTokens: Int?
        get() = if (cacheStatus == TokenCacheStatus.KNOWN) {
            (cacheHitTokens ?: 0) + (cacheMissTokens ?: 0)
        } else {
            null
        }

    val cacheHitRate: Double?
        get() = cacheInputTokens
            ?.takeIf { it > 0 }
            ?.let { denominator -> (cacheHitTokens ?: 0).toDouble() / denominator }

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
    val isToolRelated: Boolean = false,
    val cacheHitTokens: Int? = null,
    val cacheMissTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val cacheStatus: TokenCacheStatus = TokenCacheStatus.UNAVAILABLE
)

data class TokenCacheSummary(
    val hitTokens: Int,
    val missTokens: Int,
    val status: TokenCacheStatus
) {
    val hitRate: Double?
        get() = (hitTokens + missTokens)
            .takeIf { it > 0 }
            ?.let { denominator -> hitTokens.toDouble() / denominator }
}

fun List<TokenUsageRecord>.aggregateCacheUsage(): TokenCacheSummary? {
    val known = filter { usage -> usage.cacheStatus == TokenCacheStatus.KNOWN }
    if (known.isEmpty()) return null
    val summary = TokenCacheSummary(
        hitTokens = known.sumOf { usage -> usage.cacheHitTokens ?: 0 },
        missTokens = known.sumOf { usage -> usage.cacheMissTokens ?: 0 },
        status = if (any { usage -> usage.cacheStatus != TokenCacheStatus.KNOWN }) {
            TokenCacheStatus.PARTIAL
        } else {
            TokenCacheStatus.KNOWN
        }
    )
    return summary.takeIf { it.hitRate != null }
}

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
    val cacheSummary = aggregateCacheUsage()
    val cacheHitTokens = cacheSummary?.hitTokens
    val cacheMissTokens = cacheSummary?.missTokens
    val cacheWriteTokens = mapNotNull { it.cacheWriteTokens }.sum()
        .takeIf { it != 0 || any { usage -> usage.cacheWriteTokens != null } }
    val cacheStatus = cacheSummary?.status ?: when {
        any { it.cacheStatus == TokenCacheStatus.PARTIAL } -> TokenCacheStatus.PARTIAL
        else -> TokenCacheStatus.UNAVAILABLE
    }
    val details = flatMap { usage ->
        usage.details.ifEmpty {
            listOf(
                TokenUsageDetail(
                    label = label,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    totalTokens = usage.totalTokens,
                    isEstimated = usage.isEstimated,
                    isToolRelated = usage.toolTotalTokens > 0,
                    cacheHitTokens = usage.cacheHitTokens,
                    cacheMissTokens = usage.cacheMissTokens,
                    cacheWriteTokens = usage.cacheWriteTokens,
                    cacheStatus = usage.cacheStatus
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
        details = details,
        cacheHitTokens = cacheHitTokens,
        cacheMissTokens = cacheMissTokens,
        cacheWriteTokens = cacheWriteTokens,
        cacheStatus = cacheStatus
    )
}

internal fun normalizedCacheValues(
    inputTokens: Int,
    hitTokens: Int?,
    missTokens: Int?,
    writeTokens: Int?,
    allowInputOverflow: Boolean = false
): CacheUsageValues? {
    if (hitTokens == null && missTokens == null && writeTokens == null) return null
    if (listOfNotNull(hitTokens, missTokens, writeTokens).any { it < 0 }) return null
    if (hitTokens == null || missTokens == null) return null
    if (!allowInputOverflow && hitTokens + missTokens > inputTokens) return null
    return CacheUsageValues(hitTokens, missTokens, writeTokens)
}

internal fun hasInvalidCacheValues(
    inputTokens: Int,
    hitTokens: Int?,
    missTokens: Int?,
    allowInputOverflow: Boolean = false
): Boolean = listOfNotNull(hitTokens, missTokens).any { it < 0 } ||
    (!allowInputOverflow && hitTokens != null && missTokens != null && hitTokens + missTokens > inputTokens)

internal data class CacheUsageValues(
    val hitTokens: Int,
    val missTokens: Int,
    val writeTokens: Int?
)
