package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import cn.nabr.chatwithchat.data.model.ReasoningMode

@Entity(
    tableName = "chat_platform_model_v2",
    primaryKeys = ["chat_id", "platform_uid"],
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatPlatformModelV2(
    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "platform_uid")
    val platformUid: String,

    @ColumnInfo(name = "model")
    val model: String,

    @ColumnInfo(name = "reasoning_mode")
    val reasoningMode: String = ReasoningMode.OFF.storageValue,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000
)
