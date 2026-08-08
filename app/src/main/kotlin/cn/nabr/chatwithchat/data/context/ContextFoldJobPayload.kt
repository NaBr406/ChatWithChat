package cn.nabr.chatwithchat.data.context

import kotlinx.serialization.Serializable

/**
 * What one background fold has to do, as persisted on the maintenance job row.
 *
 * The payload carries *identifiers*, never message text. Turns are re-read from Room when the job
 * runs, which is what makes a fold safe to defer: a checkpoint must summarise the conversation as it
 * stands now, and an embedded snapshot taken at enqueue time could fold text the user has since
 * edited or deleted. [sourceTurnsHash] is the guard on that re-read — if the recomputed hash differs,
 * the batch changed while the job was queued and the fold is abandoned rather than written.
 */
@Serializable
data class ContextFoldJobPayload(
    val chatRoomId: Int,
    val platformUid: String,
    /** Position this fold expects to occupy in the chain. Stale if the chain moved on meanwhile. */
    val segmentIndex: Int,
    /** First user message id in the batch, inclusive. */
    val startUserMessageId: Int,
    /** Last user message id in the batch, inclusive. Becomes the new chain boundary on success. */
    val endUserMessageId: Int,
    /** [ConversationCheckpoint.sourceTurnsHash] of segment `segmentIndex - 1`, or null for the first. */
    val previousSegmentHash: String? = null,
    /** [ContextTurnHasher.hashSegment] over the batch at enqueue time. */
    val sourceTurnsHash: String,
    val createdAt: Long
)
