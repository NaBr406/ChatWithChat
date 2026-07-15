package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_pending_turn",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chat_id", "user_message_id"], unique = true),
        Index(value = ["chat_id", "claimed_job_id"]),
        Index(value = ["claimed_job_id"]),
        Index(value = ["completed_at"])
    ]
)
data class MemoryPendingTurn(
    @PrimaryKey
    @ColumnInfo(name = "turn_key")
    val turnKey: String,

    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "user_message_id")
    val userMessageId: Int,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long,

    @ColumnInfo(name = "claimed_job_id")
    val claimedJobId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
