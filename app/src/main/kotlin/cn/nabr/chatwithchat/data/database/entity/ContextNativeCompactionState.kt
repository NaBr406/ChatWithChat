package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Local mirror of the official OpenAI Responses API compaction state for one `chat_id +
 * platform_uid`.
 *
 * [compactionItemsJson] holds the provider's **opaque** canonical output window verbatim. It must
 * never be decrypted, parsed, trimmed or rendered: it is not a human-readable summary, and treating
 * it as one silently corrupts this path. The user-visible transcript stays `messages_v2`.
 */
@Entity(
    tableName = "context_native_compaction_state",
    primaryKeys = ["chat_id", "platform_uid"],
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chat_id"])]
)
data class ContextNativeCompactionState(
    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "platform_uid")
    val platformUid: String,

    /**
     * Raw JSON array of the canonical output window: the newest `type=compaction` item followed by
     * the still-valid items after it. Spliced straight back into the next `/v1/responses` input
     * array — V1 chains statelessly and deliberately does not use `previous_response_id`.
     */
    @ColumnInfo(name = "compaction_items_json")
    val compactionItemsJson: String,

    /** Last user message id covered by this window, so invalidation can be located. */
    @ColumnInfo(name = "boundary_user_message_id")
    val boundaryUserMessageId: Int,

    /** Version identifier over the turns this window covers, mirroring the checkpoint chain. */
    @ColumnInfo(name = "source_turns_hash")
    val sourceTurnsHash: String,

    @ColumnInfo(name = "estimated_tokens")
    val estimatedTokens: Int,

    /**
     * False while a standalone `/v1/responses/compact` initialization is still incomplete. A
     * half-built window is never submitted.
     */
    @ColumnInfo(name = "is_ready_for_input")
    val isReadyForInput: Boolean = true,

    /**
     * Set once the provider answered `unsupported_parameter` for this model, so the app stops
     * re-sending `context_management` every turn and degrades to the app-side checkpoint path.
     */
    @ColumnInfo(name = "native_unsupported")
    val nativeUnsupported: Boolean = false,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
