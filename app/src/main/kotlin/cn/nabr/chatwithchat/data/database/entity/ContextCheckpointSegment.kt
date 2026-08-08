package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import cn.nabr.chatwithchat.data.token.TokenUsageRecord

/**
 * One link of a conversation's checkpoint chain.
 *
 * Stored as a chain rather than a single overwritten row because AUTO_COMPACT's semantics are
 * `existing checkpoint + newly folded turns`: each fold produces exactly one more segment. A single
 * row would make full rebuild the only possible answer to an edit.
 *
 * Rows are keyed by `chat_id + platform_uid + segment_index` so two providers used in the same chat
 * keep completely independent chains. Deleting the chat cascades these away with it; the raw
 * `messages_v2` rows are never moved here and are never replaced by a checkpoint.
 */
@Entity(
    tableName = "context_checkpoint_segment",
    primaryKeys = ["chat_id", "platform_uid", "segment_index"],
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chat_id", "platform_uid"]),
        Index(value = ["chat_id", "platform_uid", "status"])
    ]
)
data class ContextCheckpointSegment(
    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "platform_uid")
    val platformUid: String,

    /** Zero-based position in the chain. Segment N's compaction input included segment N-1's body. */
    @ColumnInfo(name = "segment_index")
    val segmentIndex: Int,

    /** First user message id folded into this segment, inclusive. */
    @ColumnInfo(name = "start_user_message_id")
    val startUserMessageId: Int,

    /** Last user message id folded into this segment, inclusive; also this segment's boundary turn. */
    @ColumnInfo(name = "end_user_message_id")
    val endUserMessageId: Int,

    /** Semantic checkpoint text. Never a provider-opaque blob — that lives in the native state table. */
    @ColumnInfo(name = "body")
    val body: String,

    /**
     * Version identifier over exactly the turns this segment folded, computed **per segment** rather
     * than once for the whole chain, so invalidation can locate the first affected segment instead of
     * discarding everything.
     */
    @ColumnInfo(name = "source_turns_hash")
    val sourceTurnsHash: String,

    @ColumnInfo(name = "estimated_tokens")
    val estimatedTokens: Int,

    /**
     * Token cost of the compaction call that produced this segment. It belongs to no `messages_v2`
     * row, but it is real spend and must stay visible, so it is stored here in the same
     * [TokenUsageRecord] shape `messages_v2.token_usage` already uses.
     *
     * Null on the OpenAI native path: server-side compaction happens inside the same `/v1/responses`
     * call, whose returned usage already includes it. Recording it again would double-count.
     */
    @ColumnInfo(name = "compaction_usage")
    val compactionUsage: TokenUsageRecord? = null,

    /** Serialized [cn.nabr.chatwithchat.data.context.CheckpointSegmentStatus]. */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
