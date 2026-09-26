package cn.nabr.chatwithchat.data.memory

import java.time.Duration

internal data class MemoryFreshnessProfile(
    val durability: String,
    val freshness: String,
    val lifecycleState: String,
    val refreshAfterDays: Int?
)

internal object MemoryFreshnessPolicy {
    private val volatileKeySegments = setOf("version", "price", "test", "debug", "bug", "progress", "status")

    fun resolve(entry: MarkdownMemoryEntry): MemoryFreshnessProfile {
        val key = entry.canonicalKey.orEmpty()
        val volatile = key.split('.').any { segment ->
            volatileKeySegments.any { marker -> segment == marker || segment.startsWith(marker + "_") }
        }
        val project = entry.type == "project_context" || entry.scope.startsWith("project:")
        val defaults = when {
            volatile -> MemoryFreshnessProfile(MemoryDurability.EPHEMERAL, MemoryFreshness.EXPIRES, MemoryLifecycleState.ACTIVE, 7)
            project -> MemoryFreshnessProfile(MemoryDurability.ACTIVE, MemoryFreshness.REFRESHABLE, MemoryLifecycleState.ACTIVE, 30)
            entry.type == "interest" -> MemoryFreshnessProfile(MemoryDurability.DURABLE, MemoryFreshness.REFRESHABLE, MemoryLifecycleState.ACTIVE, 90)
            else -> MemoryFreshnessProfile(MemoryDurability.DURABLE, MemoryFreshness.STABLE, MemoryLifecycleState.ACTIVE, null)
        }
        return MemoryFreshnessProfile(
            durability = entry.durability ?: defaults.durability,
            freshness = entry.freshness ?: defaults.freshness,
            lifecycleState = entry.lifecycleState ?: defaults.lifecycleState,
            refreshAfterDays = entry.refreshAfterDays ?: defaults.refreshAfterDays
        )
    }

    fun isStale(entry: MarkdownMemoryEntry, nowMillis: Long): Boolean {
        require(nowMillis >= 0L) { "invalid freshness clock" }
        val days = resolve(entry).refreshAfterDays ?: return false
        if (entry.updatedAt <= 0L) return false
        val updatedAtSeconds = if (entry.updatedAt >= MILLIS_EPOCH_THRESHOLD) {
            entry.updatedAt / 1_000L
        } else {
            entry.updatedAt
        }
        return nowMillis / 1_000L - updatedAtSeconds >= Duration.ofDays(days.toLong()).seconds
    }

    fun allowsQuery(entry: MarkdownMemoryEntry, nowMillis: Long): Boolean {
        val profile = resolve(entry)
        return entry.validity == MemoryValidity.CURRENT &&
            profile.lifecycleState == MemoryLifecycleState.ACTIVE &&
            !isStale(entry, nowMillis)
    }

    fun allowsCore(entry: MarkdownMemoryEntry, nowMillis: Long = Long.MAX_VALUE): Boolean {
        val profile = resolve(entry)
        val projectOrVolatile = entry.type == "project_context" ||
            entry.scope.startsWith("project:") ||
            entry.canonicalKey.orEmpty().split('.').any { segment ->
                volatileKeySegments.any { marker -> segment == marker || segment.startsWith(marker + "_") }
            }
        return !projectOrVolatile &&
            entry.source != MemorySource.ASSISTANT_INFERRED &&
            profile.durability == MemoryDurability.DURABLE &&
            profile.freshness == MemoryFreshness.STABLE &&
            profile.lifecycleState == MemoryLifecycleState.ACTIVE &&
            !isStale(entry, nowMillis)
    }

    private const val MILLIS_EPOCH_THRESHOLD = 100_000_000_000L
}
