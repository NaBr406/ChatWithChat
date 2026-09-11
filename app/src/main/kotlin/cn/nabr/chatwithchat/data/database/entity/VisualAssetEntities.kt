package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "visual_asset",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["compacted_path"])
    ]
)
data class VisualAssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "asset_hash")
    val assetHash: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "original_path")
    val originalPath: String,

    @ColumnInfo(name = "original_width")
    val originalWidth: Int?,

    @ColumnInfo(name = "original_height")
    val originalHeight: Int?,

    @ColumnInfo(name = "original_size_bytes")
    val originalSizeBytes: Long,

    @ColumnInfo(name = "compacted_path")
    val compactedPath: String? = null,

    @ColumnInfo(name = "compacted_mime_type")
    val compactedMimeType: String? = null,

    @ColumnInfo(name = "compacted_width")
    val compactedWidth: Int? = null,

    @ColumnInfo(name = "compacted_height")
    val compactedHeight: Int? = null,

    @ColumnInfo(name = "compacted_size_bytes")
    val compactedSizeBytes: Long? = null,

    @ColumnInfo(name = "compacted_version")
    val compactedVersion: Int? = null,

    @ColumnInfo(name = "compaction_error")
    val compactionError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "visual_asset_catalog",
    primaryKeys = ["chat_id", "visual_ref"],
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MessageV2::class,
            parentColumns = ["message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VisualAssetEntity::class,
            parentColumns = ["asset_hash"],
            childColumns = ["asset_hash"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["asset_hash"]),
        Index(value = ["message_id"]),
        Index(value = ["chat_id", "turn_ordinal", "message_id", "attachment_index"])
    ]
)
data class VisualAssetCatalogEntity(
    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "visual_ref")
    val visualRef: String,

    @ColumnInfo(name = "asset_hash")
    val assetHash: String,

    @ColumnInfo(name = "message_id")
    val messageId: Int,

    @ColumnInfo(name = "attachment_index")
    val attachmentIndex: Int,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "width")
    val width: Int?,

    @ColumnInfo(name = "height")
    val height: Int?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "folded_at")
    val foldedAt: Long? = null,

    @ColumnInfo(name = "compacted_profile")
    val compactedProfile: String? = null
)

@Entity(
    tableName = "message_visual_ref",
    primaryKeys = ["message_id", "attachment_index"],
    foreignKeys = [
        ForeignKey(
            entity = MessageV2::class,
            parentColumns = ["message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VisualAssetEntity::class,
            parentColumns = ["asset_hash"],
            childColumns = ["asset_hash"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["asset_hash"]),
        Index(value = ["message_id"])
    ]
)
data class MessageVisualRefEntity(
    @ColumnInfo(name = "message_id")
    val messageId: Int,

    @ColumnInfo(name = "attachment_index")
    val attachmentIndex: Int,

    @ColumnInfo(name = "asset_hash")
    val assetHash: String,

    @ColumnInfo(name = "visual_ref")
    val visualRef: String
)

@Entity(
    tableName = "visual_asset_provider_ref",
    primaryKeys = ["asset_hash", "profile", "platform_uid"],
    foreignKeys = [
        ForeignKey(
            entity = VisualAssetEntity::class,
            parentColumns = ["asset_hash"],
            childColumns = ["asset_hash"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["asset_hash"]),
        Index(value = ["platform_uid"])
    ]
)
data class VisualAssetProviderRefEntity(
    @ColumnInfo(name = "asset_hash")
    val assetHash: String,

    @ColumnInfo(name = "profile")
    val profile: String,

    @ColumnInfo(name = "platform_uid")
    val platformUid: String,

    @ColumnInfo(name = "remote_type")
    val remoteType: String,

    @ColumnInfo(name = "remote_id")
    val remoteId: String,

    @ColumnInfo(name = "remote_name")
    val remoteName: String? = null,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
