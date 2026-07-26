package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sticker_assets")
data class StickerAssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "asset_key")
    val assetKey: String,
    @ColumnInfo(name = "storage_kind")
    val storageKind: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "media_kind")
    val mediaKind: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "poster_asset_key")
    val posterAssetKey: String? = null,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
    @ColumnInfo(name = "loop_count")
    val loopCount: Int? = null,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long,
    @ColumnInfo(name = "width")
    val width: Int,
    @ColumnInfo(name = "height")
    val height: Int
)
