package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The user-selected context range for one `chat_id + platform_uid`.
 *
 * A range only decides *which* turns participate in context assembly. It never deletes or hides a
 * message: the full transcript stays in `messages_v2` and stays visible. Switching provider inside a
 * chat reads that provider's own row, so ranges do not leak across providers.
 */
@Entity(
    tableName = "context_range_state",
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
data class ContextRangeState(
    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "platform_uid")
    val platformUid: String,

    /** User message id of the first turn in the range. Null means "from the start of the chat". */
    @ColumnInfo(name = "start_user_message_id")
    val startUserMessageId: Int? = null,

    /**
     * Null is the *current sentinel*: the range always extends to the newest turn, which is what V1
     * writes, because the range picker offers "from turn X to now". The column exists so a future
     * frozen end-point needs no further migration.
     */
    @ColumnInfo(name = "end_user_message_id")
    val endUserMessageId: Int? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
