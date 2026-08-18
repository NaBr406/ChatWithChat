package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_text_skill_bindings",
    primaryKeys = ["chat_id", "package_id", "skill_id", "variant_key"],
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chat_id", "order_index"]), Index(value = ["package_id"])]
)
data class SessionTextSkillBinding(
    @ColumnInfo(name = "chat_id") val chatId: Int,
    @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "skill_id") val skillId: String,
    @ColumnInfo(name = "variant_key") val variantKey: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "message_text_skill_invocations",
    foreignKeys = [
        ForeignKey(
            entity = MessageV2::class,
            parentColumns = ["message_id"],
            childColumns = ["user_message_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["user_message_id"], unique = true), Index(value = ["chat_id"])]
)
data class MessageTextSkillInvocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "user_message_id") val userMessageId: Int,
    @ColumnInfo(name = "chat_id") val chatId: Int,
    @ColumnInfo(name = "snapshot_schema_version") val snapshotSchemaVersion: Int,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
    @ColumnInfo(name = "prompt_hash") val promptHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
