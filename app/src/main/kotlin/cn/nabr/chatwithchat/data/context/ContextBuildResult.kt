package cn.nabr.chatwithchat.data.context

/**
 * Everything the request-assembly layer and the UI need to know about how one context was built.
 *
 * It carries the *decisions*, not the payload: which strategy and range applied, where the raw tail
 * begins, what was compacted, and what had to be degraded. Tests assert on this instead of trying to
 * reverse-engineer a provider request body.
 */
data class ContextBuildResult(
    val strategy: ContextStrategy,
    val range: ContextRange,
    val budget: ContextBudget,
    /** Turns sent verbatim, oldest first, with the current user message last. */
    val turns: List<ConversationTurn> = emptyList(),
    /**
     * Semantic checkpoint prefixed to the raw tail, or null when everything fits raw. Always null on
     * the OpenAI native path, where the fold lives in [nativeCompactionItemsJson] instead.
     */
    val checkpointBody: String? = null,
    /** Last user message id already folded into [checkpointBody]. Null when nothing is folded. */
    val checkpointBoundaryUserMessageId: Long? = null,
    /**
     * Opaque OpenAI compaction items replayed as input items. Never parsed or displayed.
     */
    val nativeCompactionItemsJson: String? = null,
    val estimatedTokens: Int = 0,
    val compactionState: ContextCompactionState = ContextCompactionState.NONE,
    /**
     * How many turns from a different provider had provider-specific structure stripped so they
     * could be replayed as plain text. Zero is the normal case; it is a counter rather than a flag
     * so tests can assert the exact number of degraded turns.
     */
    val crossProviderDegradedTurnCount: Int = 0,
    /** True once the range was auto-corrected because its anchor no longer resolves. */
    val rangeCorrection: ContextRangeCorrection = ContextRangeCorrection.NONE
) {
    /** RAW_PREFERRED approaching its limit — surfaced as a warning, never as a blocking prompt. */
    val isNearBudgetLimit: Boolean
        get() = strategy == ContextStrategy.RAW_PREFERRED && estimatedTokens >= budget.rawWarningThreshold

    val didCompact: Boolean
        get() = compactionState != ContextCompactionState.NONE &&
            compactionState != ContextCompactionState.PENDING_BACKGROUND_FOLD
}

/**
 * Which mechanism produced the context that is about to be sent. Drives the lightweight
 * "已自动压缩较早上下文" status, and lets tests distinguish a real fold from a fallback that merely
 * looks like one.
 */
enum class ContextCompactionState {
    /** Everything fit raw. */
    NONE,

    /** A previously stored, still-valid app-side checkpoint chain was reused. */
    REUSED_CHECKPOINT,

    /** New turns were folded into the chain during this build. */
    COMPACTED_APP_SIDE,

    /** OpenAI server-side compaction is enabled for this request via `context_management`. */
    NATIVE_SERVER_SIDE,

    /**
     * A stored opaque native window was replayed as an input item.
     */
    REUSED_NATIVE_STATE,

    /**
     * Compaction was unavailable — it failed, was cancelled, timed out, returned something
     * unparseable, or a background fold has not landed yet — so the deterministic extractive
     * summarizer filled in. The request still goes out; nothing is discarded.
     */
    DETERMINISTIC_FALLBACK,

    /**
     * Over the pre-fold line and a background job has been enqueued, but this request is still
     * within budget and goes out unchanged. Compaction never blocks the send path.
     */
    PENDING_BACKGROUND_FOLD
}
