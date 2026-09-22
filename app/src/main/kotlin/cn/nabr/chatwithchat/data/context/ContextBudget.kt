package cn.nabr.chatwithchat.data.context

import kotlin.math.min

/**
 * The single place where "how many tokens may this request use" is decided.
 *
 * Everything is derived from [modelContextWindow], the *model's* real input window as returned by
 * [ContextWindowResolver]. It must never be built from [ProviderContextPolicy.maxContextTokens],
 * which is only the legacy extractive path's local construction budget: doing so would make
 * RAW_PREFERRED compact on the very first turn.
 *
 * The invariants asserted in [check] hold for every provider and every window size, including the
 * smallest one the app supports.
 */
data class ContextBudget(
    val modelContextWindow: Int,
    val outputReserve: Int,
    val safetyMargin: Int,
    val effectiveInputBudget: Int,
    /** Background pre-fold starts here. Strictly below [compactTrigger]; the gap is the async window. */
    val preFoldTrigger: Int,
    /**
     * The upper line: history past this point must already be folded.
     *
     * App-side there is no synchronous fold-before-send — the deterministic extractive summary is the
     * floor instead, so nothing blocks the send path and this line is never *acted* on locally. It is
     * still the app's stated limit, which is why it is what gets handed to OpenAI as the server-side
     * compaction threshold (see [OpenAINativeCompactionService.compactThresholdFor]) and why the
     * invariants in [check] keep the other quantities below it.
     */
    val compactTrigger: Int,
    /** How much of the budget the most recent verbatim turns may occupy. */
    val recentRawTail: Int,
    val summaryTarget: Int,
    /** Which ceilings produced this budget. Carried so the UI and tests can name the choice. */
    val tier: ContextBudgetTier = ContextBudgetTier.DEFAULT
) {
    /** RAW_PREFERRED warns here (Task 7) without changing any user setting. */
    val rawWarningThreshold: Int get() = (effectiveInputBudget * RAW_WARNING_RATIO).toInt()

    /**
     * The point at which RAW_PREFERRED may prepare an app-side checkpoint in the background.
     *
     * This is deliberately independent of [preFoldTrigger]. [preFoldTrigger] is an AUTO_COMPACT
     * construction line and is additionally capped by the selected tier; on a large window that made
     * RAW_PREFERRED start folding at roughly 76.8K even though its verbatim allowance covered almost
     * the whole model window. RAW_PREFERRED already exposes its own warning at 80% of the effective
     * input budget, so preparation starts there while activation still waits for actual overflow.
     */
    val rawPreferredFoldTrigger: Int get() = rawWarningThreshold

    /**
     * Whether an *unclamped* history size has grown past the pre-fold line.
     *
     * The argument must be a figure that grows with the conversation. Only [ContextStrategy]s where
     * [ContextStrategy.sendsRawWhileCapacityAllows] holds can supply one, because only there does the
     * assembled request keep growing with the history. Under the AUTO_COMPACT family the request is
     * deliberately bounded by [recentRawTail] + [summaryTarget], which on any real window sits far
     * below [preFoldTrigger] — passing that here is what kept background folding from ever running,
     * and it cannot be fixed by moving the line, since every tier scales both sides together.
     */
    fun shouldPreFold(unclampedHistoryTokens: Int): Boolean = unclampedHistoryTokens >= preFoldTrigger

    /**
     * Smallest unfolded batch worth spending a background provider call on.
     *
     * A fold replaces the batch with a checkpoint segment of at most [summaryTarget] tokens, so a
     * batch no larger than that buys nothing: it would pay for an LLM call and leave the context the
     * same size. Twice the target is the smallest multiple that still guarantees a real reduction,
     * and it keeps folding proportional to fidelity — a tier that buys richer checkpoints also waits
     * for correspondingly larger batches, so the number of calls per conversation stays flat.
     *
     * Capped below [compactTrigger] for the pathological windows where [of] had to squeeze
     * [summaryTarget] up against the compaction line: doubling it there would push the threshold past
     * the point the history is supposed to be folded by, and folding would only ever start after the
     * extractive fallback had permanently taken over. [check] guarantees the cap still leaves the
     * threshold above [summaryTarget], so the "a fold must shrink something" property survives it.
     */
    val backgroundFoldMinBatchTokens: Int
        get() = (summaryTarget * BACKGROUND_FOLD_MIN_BATCH_MULTIPLE).coerceAtMost(compactTrigger - 1)

    fun exceedsBudget(estimatedTokens: Int): Boolean = estimatedTokens > effectiveInputBudget

    /**
     * Fails fast on any budget that violates the plan's invariants. Called from the factory so a bad
     * window or a future edit to the constants cannot silently produce a nonsensical budget.
     */
    fun check(): ContextBudget {
        check(recentRawTail > 0) { "recentRawTail must be positive, was $recentRawTail" }
        check(recentRawTail + summaryTarget < compactTrigger) {
            "recentRawTail($recentRawTail) + summaryTarget($summaryTarget) must be < compactTrigger($compactTrigger)"
        }
        check(preFoldTrigger < compactTrigger) {
            "preFoldTrigger($preFoldTrigger) must be < compactTrigger($compactTrigger)"
        }
        check(compactTrigger <= effectiveInputBudget) {
            "compactTrigger($compactTrigger) must be <= effectiveInputBudget($effectiveInputBudget)"
        }
        check(effectiveInputBudget <= modelContextWindow) {
            "effectiveInputBudget($effectiveInputBudget) must be <= modelContextWindow($modelContextWindow)"
        }
        return this
    }

    companion object {
        /**
         * Defensive floor so a tiny or misreported window cannot produce a zero or negative budget.
         * Large enough to still hold a system prompt plus one modest turn.
         */
        const val MIN_INPUT_BUDGET = 2_048

        /**
         * Cost control, **not** a protocol constraint, and no longer a single constant: the ceilings
         * now come from [ContextBudgetTier] so the trade-off is the user's to make per provider.
         * [ContextBudgetTier.BALANCED] holds the value this constant used to have.
         */
        private const val OUTPUT_RESERVE_RATIO = 0.15
        private const val OUTPUT_RESERVE_CEILING = 8_192
        private const val SAFETY_MARGIN_RATIO = 0.05
        private const val SAFETY_MARGIN_CEILING = 2_048
        private const val PRE_FOLD_RATIO = 0.60
        private const val COMPACT_TRIGGER_RATIO = 0.75
        private const val RECENT_RAW_TAIL_RATIO = 0.30
        private const val RAW_WARNING_RATIO = 0.80

        /**
         * How many times [summaryTarget] a pending batch must reach before a fold is worth paying
         * for. See [backgroundFoldMinBatchTokens].
         */
        private const val BACKGROUND_FOLD_MIN_BATCH_MULTIPLE = 2

        private const val SUMMARY_TARGET_MIN = 768

        fun of(
            modelContextWindow: Int,
            providerSummaryTarget: Int,
            tier: ContextBudgetTier = ContextBudgetTier.DEFAULT
        ): ContextBudget {
            val window = modelContextWindow.coerceAtLeast(MIN_INPUT_BUDGET)
            val outputReserve = min(window * OUTPUT_RESERVE_RATIO, OUTPUT_RESERVE_CEILING.toDouble()).toInt()
            val safetyMargin = min(window * SAFETY_MARGIN_RATIO, SAFETY_MARGIN_CEILING.toDouble()).toInt()
            // At exactly MIN_INPUT_BUDGET the floor wins and the reserves are effectively zero. That
            // is the plan's `max(..., MIN_INPUT_BUDGET)` behaving as intended: a window this small is
            // already misconfigured, and a zero or negative budget would be strictly worse.
            val effectiveInputBudget = (window - outputReserve - safetyMargin).coerceAtLeast(MIN_INPUT_BUDGET)

            val uncappedCompactTriggerRaw = effectiveInputBudget * COMPACT_TRIGGER_RATIO
            // A null ceiling is FOLLOW_WINDOW: the ratio applies to the real window untouched.
            val compactTriggerRaw = tier.compactTriggerCeiling
                ?.let { ceiling -> min(uncappedCompactTriggerRaw, ceiling.toDouble()) }
                ?: uncappedCompactTriggerRaw
            val compactTrigger = compactTriggerRaw.toInt().coerceAtMost(effectiveInputBudget)
            // On a large window the tier's ceiling binds, and a plain `effectiveInputBudget * 0.60`
            // would land far above compactTrigger. Clamping it to 1 below would technically satisfy
            // the invariant while leaving the background job no time at all, so the ratio between the
            // two lines is preserved instead: whenever a ceiling binds, preFoldTrigger keeps the same
            // 0.60/0.75 proportion of compactTrigger. The *un-truncated* trigger is used here so that
            // when no ceiling binds — every tier on a small window, and FOLLOW_WINDOW always — this
            // reduces exactly to `effectiveInputBudget * 0.60` rather than one token below.
            val preFoldTrigger = if (compactTriggerRaw == uncappedCompactTriggerRaw) {
                // Keep the non-ceiling path exact; floating-point 0.60 / 0.75 can truncate one
                // token below the documented effective-budget ratio.
                (effectiveInputBudget * PRE_FOLD_RATIO).toInt()
            } else {
                (compactTriggerRaw * (PRE_FOLD_RATIO / COMPACT_TRIGGER_RATIO)).toInt()
            }.coerceIn(1, (compactTrigger - 1).coerceAtLeast(1))
            val uncappedRecentRawTail = effectiveInputBudget * RECENT_RAW_TAIL_RATIO
            val recentRawTail = (
                tier.recentRawTailCeiling
                    ?.let { ceiling -> min(uncappedRecentRawTail, ceiling.toDouble()) }
                    ?: uncappedRecentRawTail
                ).toInt().coerceAtLeast(1)

            // The provider's own summary budget is only ever 700..1_200, so a tier that merely raised
            // a ceiling would change nothing. Scaling first is what makes a higher tier buy a more
            // detailed checkpoint; the tier ceiling then bounds the result.
            val summaryCeiling = tier.summaryTargetCeiling.coerceAtLeast(SUMMARY_TARGET_MIN)
            val scaledSummaryTarget = (providerSummaryTarget * tier.summaryScale).toInt()

            // summaryTarget is clamped by the tier, then squeezed further if a very small window
            // would otherwise break `recentRawTail + summaryTarget < compactTrigger`. The raw tail
            // is what the user actually reads back, so the summary yields, never the tail. This is
            // reachable on ordinary windows now, not just pathological ones: a small-window provider
            // on FOLLOW_WINDOW scales its summary 4x while its compactTrigger stays ratio-bound.
            val clampedSummaryTarget = scaledSummaryTarget.coerceIn(SUMMARY_TARGET_MIN, summaryCeiling)
            val summaryHeadroom = compactTrigger - recentRawTail - 1
            val summaryTarget = clampedSummaryTarget.coerceAtMost(summaryHeadroom.coerceAtLeast(1))

            // A window small enough to leave no headroom at all cannot honour the invariants: shrink
            // the raw tail until it can. Only reachable on pathologically small configured windows.
            val adjustedRawTail = if (recentRawTail + summaryTarget >= compactTrigger) {
                (compactTrigger - summaryTarget - 1).coerceAtLeast(1)
            } else {
                recentRawTail
            }

            return ContextBudget(
                modelContextWindow = window,
                outputReserve = outputReserve,
                safetyMargin = safetyMargin,
                effectiveInputBudget = effectiveInputBudget,
                preFoldTrigger = preFoldTrigger,
                compactTrigger = compactTrigger,
                recentRawTail = adjustedRawTail,
                summaryTarget = summaryTarget,
                tier = tier
            ).check()
        }

        fun of(
            modelContextWindow: Int,
            policy: ProviderContextPolicy,
            tier: ContextBudgetTier = ContextBudgetTier.DEFAULT
        ): ContextBudget = of(modelContextWindow, policy.summaryTokenBudget, tier)
    }
}
