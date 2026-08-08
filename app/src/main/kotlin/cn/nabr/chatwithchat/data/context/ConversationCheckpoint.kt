package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.token.TokenUsageRecord

/**
 * One segment of a conversation's checkpoint chain.
 *
 * The chain — not a single overwritten row — is the unit of storage, because AUTO_COMPACT's
 * incremental semantics are `existing checkpoint + newly folded turns`: every fold naturally
 * produces one more link. Storing it as a single row would leave full rebuild as the only possible
 * response to an edit.
 *
 * Segments are immutable once [CheckpointSegmentStatus.VALID]. An edit marks segment K and every
 * later segment [CheckpointSegmentStatus.INVALID] without deleting any body, so segments `0..K-1`
 * stay usable while the tail is replayed raw.
 */
data class ConversationCheckpoint(
    val chatRoomId: Long,
    val platformUid: String,
    /** Zero-based position in the chain. Segment N's compaction input included segment N-1's body. */
    val segmentIndex: Int,
    /** First user message id folded into this segment, inclusive. */
    val startUserMessageId: Long,
    /** Last user message id folded into this segment, inclusive. Also this segment's boundary turn. */
    val endUserMessageId: Long,
    /** Semantic checkpoint text. Never a provider-opaque blob — see [OpenAINativeCompactionState]. */
    val body: String,
    /**
     * Version identifier over exactly the turns this segment folded, computed **per segment** rather
     * than once for the whole chain, so invalidation can locate the first affected segment instead of
     * discarding everything.
     */
    val sourceTurnsHash: String,
    val estimatedTokens: Int,
    /**
     * Token cost of the compaction call that produced this segment, so it is visible in session
     * totals even though it belongs to no `messages_v2` row.
     *
     * Null on the OpenAI native path: server-side compaction happens inside the same
     * `/v1/responses` call, whose usage already includes it. Recording it again would double-count.
     */
    val compactionUsage: TokenUsageRecord? = null,
    val status: CheckpointSegmentStatus = CheckpointSegmentStatus.VALID,
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val updatedAt: Long = System.currentTimeMillis() / 1000
)

enum class CheckpointSegmentStatus {
    VALID,

    /**
     * A folded turn was edited, deleted or retried. The body is kept deliberately: it is still a
     * correct summary of what it originally folded, it just no longer matches the live conversation.
     * Keeping it lets a later rebuild diff instead of starting over, and guarantees nothing is lost
     * if the rebuild fails.
     */
    INVALID,

    /** A background fold is in flight for this segment. Never blocks the synchronous send path. */
    PENDING
}

/**
 * The prefix of the chain that is safe to use right now, plus the boundary the raw tail must resume
 * from. Produced by walking segments in order and stopping at the first non-[CheckpointSegmentStatus.VALID] one.
 */
data class CheckpointChainView(
    val validSegments: List<ConversationCheckpoint> = emptyList(),
    val invalidatedFromSegmentIndex: Int? = null
) {
    val isEmpty: Boolean get() = validSegments.isEmpty()

    /** Combined body of the usable prefix, oldest segment first. */
    val checkpointBody: String?
        get() = validSegments
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n\n") { it.body.trim() }
            ?.takeIf { it.isNotBlank() }

    /**
     * Last user message id already folded by the usable prefix. Turns after this participate raw.
     * Null when no segment is usable, meaning the whole range is raw.
     */
    val foldedThroughUserMessageId: Long?
        get() = validSegments.lastOrNull()?.endUserMessageId

    val estimatedTokens: Int get() = validSegments.sumOf { it.estimatedTokens }

    val nextSegmentIndex: Int get() = validSegments.size
}
