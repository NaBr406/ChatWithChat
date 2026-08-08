package cn.nabr.chatwithchat.data.context

/**
 * The user-selected slice of a conversation that participates in context assembly, keyed by
 * `chatRoomId + platformUid`.
 *
 * Scope is deliberately independent of [ContextStrategy]: the range decides *which* turns take part,
 * the strategy decides whether they are sent raw or folded into a checkpoint. A range never deletes
 * or hides messages — the full chat log stays in Room and stays visible.
 *
 * Endpoints snap to complete turn boundaries. At the persistence layer a turn is exactly one user
 * message plus its assistant reply, because `MessageV2` has no tool-call or tool-result columns:
 * tool traffic exists only in the in-memory state of a single provider request and is never stored.
 * Keeping tool calls paired is therefore a request-assembly concern, not a range concern.
 */
data class ContextRange(
    val chatRoomId: Long,
    val platformUid: String,
    /**
     * User message id of the first turn in the range, inclusive. `null` means "from the beginning of
     * the conversation", which is also the default for a chat the user has never adjusted.
     */
    val startUserMessageId: Long? = null
) {
    val isFullHistory: Boolean get() = startUserMessageId == null

    companion object {
        fun fullHistory(chatRoomId: Long, platformUid: String): ContextRange = ContextRange(
            chatRoomId = chatRoomId,
            platformUid = platformUid,
            startUserMessageId = null
        )
    }
}

/**
 * Why a stored range no longer matched the conversation, so the UI can explain the correction rather
 * than appear to have silently changed the user's choice.
 *
 * Correction never removes messages: an invalid endpoint widens the range back toward full history.
 */
enum class ContextRangeCorrection {
    NONE,

    /** The anchor turn was deleted, or belongs to a different chat. Range reset to full history. */
    ANCHOR_MISSING,

    /** The anchor existed but was not a user message, so it could not be a turn boundary. */
    ANCHOR_NOT_A_TURN_BOUNDARY
}
