package cn.nabr.chatwithchat.data.context

/**
 * How much of a model's window a single request is allowed to spend.
 *
 * This is the cost dial, kept separate from *how* history is organised. On a small-window model the
 * ratios in [ContextBudget] bind and every tier behaves identically; the tier only starts to matter
 * once the window is large enough for the ceilings to be the thing actually deciding request size.
 * That is exactly the case the ceilings exist for — a 400k/1M window model would otherwise let one
 * request grow enormously expensive without the user ever choosing that.
 *
 * [BALANCED] reproduces the previous hard-coded constants bit for bit and is the default, so an
 * existing provider that has never seen this setting keeps behaving exactly as it did.
 *
 * [FOLLOW_WINDOW] removes the two ceilings entirely and lets the ratios run against the real window.
 * It is *not* the same thing as [ContextStrategy.RAW_PREFERRED]: this still assembles a checkpoint
 * plus a proportional raw tail, so request size stays a predictable fraction of the window, whereas
 * RAW_PREFERRED hands the whole remaining budget to verbatim turns.
 */
enum class ContextBudgetTier(
    /** Upper bound on [ContextBudget.compactTrigger]; null means "no ceiling, follow the window". */
    val compactTriggerCeiling: Int?,
    /** Upper bound on [ContextBudget.recentRawTail]; null means "no ceiling, follow the window". */
    val recentRawTailCeiling: Int?,
    /**
     * Multiplier applied to the provider's own summary budget.
     *
     * A multiplier rather than a ceiling because [ProviderContextPolicy.summaryTokenBudget] is only
     * ever 700..1_200, so raising a ceiling alone would change nothing — the provider value never
     * reaches it. Scaling is what actually lets a higher tier buy a more detailed checkpoint.
     */
    val summaryScale: Double,
    /** Hard bound applied after [summaryScale], so a future provider value cannot escape the tier. */
    val summaryTargetCeiling: Int
) {
    /**
     * Folds early and keeps the raw tail short. For providers billed per token where continuity
     * matters less than cost, or for a model whose window is large but whose price is not.
     */
    ECONOMY(
        compactTriggerCeiling = 32_000,
        recentRawTailCeiling = 8_000,
        summaryScale = 0.75,
        summaryTargetCeiling = 1_024
    ),

    /** The previous fixed behaviour, unchanged: 96_000 / 16_000 / 2_048. */
    BALANCED(
        compactTriggerCeiling = 96_000,
        recentRawTailCeiling = 16_000,
        summaryScale = 1.0,
        summaryTargetCeiling = 2_048
    ),

    /**
     * Roughly the ceiling the official compaction example uses. Noticeably better continuity on long
     * conversations at a proportionally higher per-request cost.
     */
    GENEROUS(
        compactTriggerCeiling = 200_000,
        recentRawTailCeiling = 48_000,
        summaryScale = 2.0,
        summaryTargetCeiling = 4_096
    ),

    /**
     * No ceiling at all — [ContextBudget]'s ratios apply to the model's real window.
     *
     * The expensive one, deliberately so. On a 1M-window model this puts the compaction line near
     * 742k tokens and the raw tail near 297k, which is the point, but it means a single long
     * conversation can cost two orders of magnitude more per request than [BALANCED].
     */
    FOLLOW_WINDOW(
        compactTriggerCeiling = null,
        recentRawTailCeiling = null,
        summaryScale = 4.0,
        summaryTargetCeiling = 8_192
    );

    /** True when neither ceiling applies, so the budget is a pure function of the window. */
    val followsWindow: Boolean
        get() = compactTriggerCeiling == null && recentRawTailCeiling == null

    companion object {
        val DEFAULT: ContextBudgetTier = BALANCED
    }
}
