package cn.nabr.chatwithchat.data.context

/**
 * Per-provider context strategy, persisted on `PlatformV2`.
 *
 * Two independent decisions are folded into one enum, because one radio list is what the settings
 * screen offers and a second dial would have cost a schema migration for a choice most users make
 * once:
 *
 *  - *How* history is organised. Every `AUTO_COMPACT_*` value assembles a request from the saved
 *    checkpoint plus a bounded recent raw tail. [RAW_PREFERRED] sends selected turns verbatim while
 *    they fit the effective input budget and compacts the earlier ones only once they no longer do.
 *  - *How much* of the model's window one request may spend, via [budgetTier].
 *
 * [RAW_PREFERRED] therefore means "raw while capacity allows", **not** "never compact". Nothing here
 * ever discards a message: only the request assembly changes.
 *
 * [RAW_PREFERRED] is deliberately not tier-adjustable. Its raw tail is already the whole remaining
 * budget, so a tier could only move the background fold boundary — a much smaller effect than the
 * choice the user is actually making — and it keeps this list from growing to eight entries.
 */
enum class ContextStrategy(
    val budgetTier: ContextBudgetTier,
    /**
     * True when verbatim turns may claim the entire remaining budget rather than a bounded tail.
     *
     * Assembly branches on this rather than on the enum value so a new tier stays a one-line addition
     * instead of another arm in every `when` that only ever cared about this distinction.
     */
    val sendsRawWhileCapacityAllows: Boolean
) {
    AUTO_COMPACT_ECONOMY(ContextBudgetTier.ECONOMY, false),
    AUTO_COMPACT_BALANCED(ContextBudgetTier.BALANCED, false),
    AUTO_COMPACT_GENEROUS(ContextBudgetTier.GENEROUS, false),
    AUTO_COMPACT_FOLLOW_WINDOW(ContextBudgetTier.FOLLOW_WINDOW, false),
    RAW_PREFERRED(ContextBudgetTier.BALANCED, true);

    /** True for the checkpoint-plus-tail family, i.e. everything except [RAW_PREFERRED]. */
    val isAutoCompact: Boolean
        get() = !sendsRawWhileCapacityAllows

    companion object {
        val DEFAULT: ContextStrategy = AUTO_COMPACT_BALANCED

        /**
         * The value written by the schema-21 column default, and by every provider row created before
         * tiers existed.
         *
         * Kept as an alias rather than renamed, because `platform_v2.context_strategy` still declares
         * `DEFAULT 'AUTO_COMPACT'` and that string is part of the exported schema's identity hash.
         * Changing it would turn a settings addition into a schema-22 migration for no user-visible
         * gain, so the aliasing lives here instead.
         */
        private const val LEGACY_AUTO_COMPACT = "AUTO_COMPACT"

        /** Tolerant parse for the persisted column; unknown or legacy values fall back to default. */
        fun fromStorage(value: String?): ContextStrategy {
            val normalized = value?.trim()?.uppercase() ?: return DEFAULT
            if (normalized == LEGACY_AUTO_COMPACT) return AUTO_COMPACT_BALANCED
            return entries.firstOrNull { it.name == normalized } ?: DEFAULT
        }
    }
}
