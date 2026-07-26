package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import cn.nabr.chatwithchat.data.database.entity.StickerAssetEntity
import cn.nabr.chatwithchat.data.database.entity.StickerItemEntity
import cn.nabr.chatwithchat.data.database.entity.StickerPackEntity
import kotlinx.coroutines.flow.Flow

data class StickerItemWithAsset(
    @Embedded
    val item: StickerItemEntity,
    @Relation(parentColumn = "asset_key", entityColumn = "asset_key")
    val asset: StickerAssetEntity?
)

@Dao
interface StickerCatalogDao {
    @Transaction
    @Query("SELECT * FROM sticker_items ORDER BY is_builtin DESC, title COLLATE NOCASE ASC, sticker_id ASC")
    fun observeItemsWithAssets(): Flow<List<StickerItemWithAsset>>

    @Transaction
    @Query("SELECT * FROM sticker_items WHERE sticker_id = :stickerId LIMIT 1")
    suspend fun getItemWithAsset(stickerId: String): StickerItemWithAsset?

    @Transaction
    @Query("SELECT * FROM sticker_items WHERE enabled = 1 ORDER BY is_builtin DESC, title COLLATE NOCASE ASC, sticker_id ASC")
    suspend fun getEnabledItemsWithAssets(): List<StickerItemWithAsset>

    @Query("SELECT * FROM sticker_items WHERE sticker_id = :stickerId LIMIT 1")
    suspend fun getItem(stickerId: String): StickerItemEntity?

    @Query("SELECT * FROM sticker_assets WHERE asset_key = :assetKey LIMIT 1")
    suspend fun getAsset(assetKey: String): StickerAssetEntity?

    @Upsert
    suspend fun upsertPack(pack: StickerPackEntity)

    @Upsert
    suspend fun upsertAsset(asset: StickerAssetEntity)

    @Upsert
    suspend fun upsertItem(item: StickerItemEntity)

    @Query(
        """
        UPDATE sticker_items
        SET title = :title, alt_text = :altText, tags_json = :tagsJson, updated_at = :updatedAt
        WHERE sticker_id = :stickerId AND is_builtin = 0
        """
    )
    suspend fun updateCustomItemMetadata(
        stickerId: String,
        title: String,
        altText: String,
        tagsJson: String,
        updatedAt: Long
    ): Int

    @Query("UPDATE sticker_items SET enabled = :enabled, updated_at = :updatedAt WHERE sticker_id = :stickerId AND is_builtin = 0")
    suspend fun updateCustomItemEnabled(stickerId: String, enabled: Boolean, updatedAt: Long): Int

    @Delete
    suspend fun deleteItem(item: StickerItemEntity)

    @Query("SELECT COUNT(*) FROM sticker_items WHERE asset_key = :assetKey")
    suspend fun countItemsForAsset(assetKey: String): Int

    @Delete
    suspend fun deleteAsset(asset: StickerAssetEntity)
}
