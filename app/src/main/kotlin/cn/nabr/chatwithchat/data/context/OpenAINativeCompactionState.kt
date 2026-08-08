package cn.nabr.chatwithchat.data.context

/**
 * Local mirror of the official OpenAI Responses API compaction state for one `chatRoomId +
 * platformUid`.
 *
 * The stored payload is the provider's **opaque** `type=compaction` output item (or the full
 * canonical output window returned by the standalone `/v1/responses/compact` endpoint), kept as raw
 * JSON and replayed verbatim as an input item on the next request.
 *
 * It must never be parsed, edited, truncated or shown to the user. It is not a human-readable
 * summary and treating it as one is the single easiest way to corrupt this path. The user-visible
 * transcript remains `messages_v2`.
 */
data class OpenAINativeCompactionState(
    val chatRoomId: Long,
    val platformUid: String,
    /**
     * Raw JSON array of the canonical output window: the newest compaction item followed by the
     * still-valid items after it. Passed straight back into the next `/v1/responses` input array
     * (stateless chaining; V1 deliberately does not use `previous_response_id`).
     */
    val compactionItemsJson: String,
    /** Last user message id covered by this native window, so invalidation can be located. */
    val boundaryUserMessageId: Long,
    /** Version identifier over the turns this window covers, mirroring [ConversationCheckpoint]. */
    val sourceTurnsHash: String,
    val estimatedTokens: Int,
    /**
     * False while a standalone `/v1/responses/compact` initialization is still incomplete. Only a
     * ready state may be sent; a half-built window is never submitted.
     */
    val isReadyForInput: Boolean = true,
    /**
     * Set once the provider has answered `unsupported_parameter` for this model, so the app stops
     * re-sending `context_management` every turn and degrades to the app-side checkpoint path.
     */
    val nativeUnsupported: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis() / 1000
) {
    val isUsable: Boolean get() = isReadyForInput && !nativeUnsupported && compactionItemsJson.isNotBlank()
}
