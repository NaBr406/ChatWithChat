package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sticker_items",
    foreignKeys = [
        ForeignKey(
            entity = StickerPackEntity::class,
            parentColumns = ["pack_id"],
            childColumns = ["pack_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = StickerAssetEntity::class,
            parentColumns = ["asset_key"],
            childColumns = ["asset_key"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["pack_id"]),
        Index(value = ["asset_key"]),
        Index(value = ["enabled"])
    ]
)
data class StickerItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "sticker_id")
    val stickerId: String,
    @ColumnInfo(name = "pack_id")
    val packId: String,
    @ColumnInfo(name = "asset_key")
    val assetKey: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "alt_text")
    val altText: String,
    @ColumnInfo(name = "tags_json")
    val tagsJson: String,
    @ColumnInfo(name = "aliases_json")
    val aliasesJson: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "is_builtin")
    val isBuiltin: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
