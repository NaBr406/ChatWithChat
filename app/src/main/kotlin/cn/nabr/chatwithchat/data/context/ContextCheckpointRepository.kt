package cn.nabr.chatwithchat.data.context

/**
 * Durable storage for everything context management remembers between requests: the app-side
 * checkpoint chain, the user's selected range, and the OpenAI native compaction state.
 *
 * Two rules run through every method here.
 *
 * First, **nothing is written before the work that produced it succeeded.** There is no "pending"
 * row to clean up after a crash, and a failed compaction leaves the previous state exactly as it
 * was.
 *
 * Second, **invalidation is lazy and never compacts.** Marking a chain invalid is a local database
 * write. It never issues a provider call, so it can safely run on the path that handles a message
 * edit without making the user wait for a network round trip.
 */
interface ContextCheckpointRepository {

    /**
     * The usable prefix of the chain: segments `0..K-1` where `K` is the first non-VALID segment.
     * An empty view means everything in the range participates raw.
     */
    suspend fun loadChain(chatRoomId: Int, platformUid: String): CheckpointChainView

    suspend fun loadAllSegments(chatRoomId: Int, platformUid: String): List<ConversationCheckpoint>

    /** Appends one link. Called only after the compaction that produced [segment] succeeded. */
    suspend fun appendSegment(segment: ConversationCheckpoint)

    /**
     * Marks the segment covering [userMessageId] and everything after it INVALID, and clears any
     * native state for the same key.
     *
     * Bodies are kept: an invalid body is still a correct summary of what it originally folded, so
     * keeping it lets a later rebuild proceed incrementally and guarantees nothing is lost if that
     * rebuild fails.
     *
     * @return the index of the first invalidated segment, or null if the edit touched a turn no
     *   segment had folded yet — the common case, and one that costs nothing.
     */
    suspend fun invalidateFrom(chatRoomId: Int, platformUid: String, userMessageId: Int): Int?

    /**
     * Commits a completed background rebuild over segments `fromSegmentIndex..`. Segments before it
     * are never touched, so a failed rebuild cannot destroy the still-valid prefix.
     */
    suspend fun replaceChainFrom(
        chatRoomId: Int,
        platformUid: String,
        fromSegmentIndex: Int,
        rebuilt: List<ConversationCheckpoint>
    )

    suspend fun clearChain(chatRoomId: Int, platformUid: String)

    /**
     * The stored range, or full history when the user has never adjusted this chat for this
     * provider. Never returns null: an absent row means "everything".
     */
    suspend fun loadRange(chatRoomId: Int, platformUid: String): ContextRange

    suspend fun saveRange(range: ContextRange)

    suspend fun clearRange(chatRoomId: Int, platformUid: String)

    suspend fun loadNativeState(chatRoomId: Int, platformUid: String): OpenAINativeCompactionState?

    suspend fun saveNativeState(state: OpenAINativeCompactionState)

    /**
     * Drops native state. Unlike an app-side body it carries no reusable meaning once stale, and the
     * next ordinary `/v1/responses` call rebuilds it server-side — which is exactly why invalidating
     * it never needs a synchronous `/v1/responses/compact` call.
     */
    suspend fun clearNativeState(chatRoomId: Int, platformUid: String)

    /**
     * Records that the provider answered `unsupported_parameter` for this model, so the app stops
     * re-sending `context_management` every turn and degrades to the app-side checkpoint path.
     */
    suspend fun markNativeUnsupported(chatRoomId: Int, platformUid: String)
}
