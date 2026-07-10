package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_chat_checkpoint",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["idle_due_at"])]
)
data class MemoryChatCheckpoint(
    @PrimaryKey
    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "last_processed_user_message_id")
    val lastProcessedUserMessageId: Int = 0,

    @ColumnInfo(name = "last_observed_user_message_id")
    val lastObservedUserMessageId: Int = 0,

    @ColumnInfo(name = "pending_since")
    val pendingSince: Long? = null,

    @ColumnInfo(name = "last_user_activity_at")
    val lastUserActivityAt: Long? = null,

    @ColumnInfo(name = "idle_due_at")
    val idleDueAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
