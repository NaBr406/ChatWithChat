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
    /** Synchronous last-resort line. Rarely reached once background pre-folding is working. */
    val compactTrigger: Int,
    /** How much of the budget the most recent verbatim turns may occupy. */
    val recentRawTail: Int,
    val summaryTarget: Int
) {
    /** RAW_PREFERRED warns here (Task 7) without changing any user setting. */
    val rawWarningThreshold: Int get() = (effectiveInputBudget * RAW_WARNING_RATIO).toInt()

    fun shouldPreFold(estimatedTokens: Int): Boolean = estimatedTokens >= preFoldTrigger

    fun shouldCompactNow(estimatedTokens: Int): Boolean = estimatedTokens >= compactTrigger

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
         * Cost control, **not** a protocol constraint. The official compaction example uses 200_000;
         * this plan deliberately picks a lower ceiling so a single request on a 400k/1M-window model
         * cannot quietly become enormously expensive. Raise it here — in one place — if that
         * trade-off ever changes.
         */
        const val COMPACT_TRIGGER_CEILING = 96_000

        private const val OUTPUT_RESERVE_RATIO = 0.15
        private const val OUTPUT_RESERVE_CEILING = 8_192
        private const val SAFETY_MARGIN_RATIO = 0.05
        private const val SAFETY_MARGIN_CEILING = 2_048
        private const val PRE_FOLD_RATIO = 0.60
        private const val COMPACT_TRIGGER_RATIO = 0.75
        private const val RECENT_RAW_TAIL_RATIO = 0.30
        private const val RECENT_RAW_TAIL_CEILING = 16_000
        private const val RAW_WARNING_RATIO = 0.80

        private const val SUMMARY_TARGET_MIN = 768
        private const val SUMMARY_TARGET_MAX = 2_048

        fun of(
            modelContextWindow: Int,
            providerSummaryTarget: Int
        ): ContextBudget {
            val window = modelContextWindow.coerceAtLeast(MIN_INPUT_BUDGET)
            val outputReserve = min(window * OUTPUT_RESERVE_RATIO, OUTPUT_RESERVE_CEILING.toDouble()).toInt()
            val safetyMargin = min(window * SAFETY_MARGIN_RATIO, SAFETY_MARGIN_CEILING.toDouble()).toInt()
            // At exactly MIN_INPUT_BUDGET the floor wins and the reserves are effectively zero. That
            // is the plan's `max(..., MIN_INPUT_BUDGET)` behaving as intended: a window this small is
            // already misconfigured, and a zero or negative budget would be strictly worse.
            val effectiveInputBudget = (window - outputReserve - safetyMargin).coerceAtLeast(MIN_INPUT_BUDGET)

            val compactTriggerRaw = min(
                effectiveInputBudget * COMPACT_TRIGGER_RATIO,
                COMPACT_TRIGGER_CEILING.toDouble()
            )
            val compactTrigger = compactTriggerRaw.toInt().coerceAtMost(effectiveInputBudget)
            // On a large window COMPACT_TRIGGER_CEILING binds, and a plain
            // `effectiveInputBudget * 0.60` would land far above compactTrigger. Clamping it to
            // 1 below would technically satisfy the invariant while leaving the background job no
            // time at all, so the ratio between the two lines is preserved instead: whenever the
            // ceiling binds, preFoldTrigger keeps the same 0.60/0.75 proportion of compactTrigger.
            // The *un-truncated* trigger is used here so that when the ceiling does not bind this
            // reduces exactly to the plan's `effectiveInputBudget * 0.60` rather than one token below.
            val preFoldTrigger = min(
                effectiveInputBudget * PRE_FOLD_RATIO,
                compactTriggerRaw * (PRE_FOLD_RATIO / COMPACT_TRIGGER_RATIO)
            ).toInt().coerceIn(1, (compactTrigger - 1).coerceAtLeast(1))
            val recentRawTail = min(
                effectiveInputBudget * RECENT_RAW_TAIL_RATIO,
                RECENT_RAW_TAIL_CEILING.toDouble()
            ).toInt().coerceAtLeast(1)

            // summaryTarget is clamped by the plan, then squeezed further if a very small window
            // would otherwise break `recentRawTail + summaryTarget < compactTrigger`. The raw tail
            // is what the user actually reads back, so the summary yields, never the tail.
            val clampedSummaryTarget = providerSummaryTarget.coerceIn(SUMMARY_TARGET_MIN, SUMMARY_TARGET_MAX)
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
                summaryTarget = summaryTarget
            ).check()
        }

        fun of(modelContextWindow: Int, policy: ProviderContextPolicy): ContextBudget =
            of(modelContextWindow, policy.summaryTokenBudget)
    }
}
