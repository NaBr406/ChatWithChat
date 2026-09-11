package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cn.nabr.chatwithchat.data.database.entity.MessageVisualRefEntity
import cn.nabr.chatwithchat.data.database.entity.VisualAssetCatalogEntity
import cn.nabr.chatwithchat.data.database.entity.VisualAssetEntity
import cn.nabr.chatwithchat.data.database.entity.VisualAssetProviderRefEntity

@Dao
interface VisualAssetDao {

    @Query("SELECT * FROM visual_asset WHERE asset_hash = :assetHash LIMIT 1")
    suspend fun getAsset(assetHash: String): VisualAssetEntity?

    @Query("SELECT * FROM visual_asset")
    suspend fun getAllAssets(): List<VisualAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAsset(asset: VisualAssetEntity)

    @Query(
        """
        SELECT * FROM visual_asset_catalog
        WHERE chat_id = :chatId AND visual_ref = :visualRef
        LIMIT 1
        """
    )
    suspend fun getCatalogEntry(chatId: Int, visualRef: String): VisualAssetCatalogEntity?

    @Query(
        """
        SELECT * FROM visual_asset_catalog
        WHERE chat_id = :chatId
        ORDER BY turn_ordinal ASC, message_id ASC, attachment_index ASC
        """
    )
    suspend fun getCatalogEntries(chatId: Int): List<VisualAssetCatalogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogEntries(entries: List<VisualAssetCatalogEntity>)

    @Query("DELETE FROM visual_asset_catalog WHERE message_id = :messageId")
    suspend fun deleteCatalogEntriesForMessage(messageId: Int)

    @Query(
        """
        UPDATE visual_asset_catalog
        SET folded_at = :foldedAt, compacted_profile = :profile
        WHERE chat_id = :chatId AND message_id IN (:messageIds)
        """
    )
    suspend fun markCatalogEntriesFolded(
        chatId: Int,
        messageIds: List<Int>,
        foldedAt: Long,
        profile: String
    )

    @Query("SELECT * FROM message_visual_ref WHERE message_id = :messageId ORDER BY attachment_index ASC")
    suspend fun getMessageRefs(messageId: Int): List<MessageVisualRefEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessageRefs(refs: List<MessageVisualRefEntity>)

    @Query("DELETE FROM message_visual_ref WHERE message_id = :messageId")
    suspend fun deleteMessageRefs(messageId: Int)

    @Query("SELECT COUNT(*) FROM message_visual_ref WHERE asset_hash = :assetHash")
    suspend fun countMessageRefsForAsset(assetHash: String): Int

    @Query(
        """
        SELECT * FROM visual_asset_provider_ref
        WHERE asset_hash = :assetHash AND profile = :profile AND platform_uid = :platformUid
        LIMIT 1
        """
    )
    suspend fun getProviderRef(
        assetHash: String,
        profile: String,
        platformUid: String
    ): VisualAssetProviderRefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviderRef(providerRef: VisualAssetProviderRefEntity)

    @Query("DELETE FROM visual_asset_provider_ref WHERE asset_hash = :assetHash")
    suspend fun deleteProviderRefsForAsset(assetHash: String)

    @Query(
        """
        DELETE FROM visual_asset
        WHERE asset_hash = :assetHash
          AND NOT EXISTS (
              SELECT 1 FROM message_visual_ref
              WHERE message_visual_ref.asset_hash = visual_asset.asset_hash
          )
        """
    )
    suspend fun deleteAssetIfUnreferenced(assetHash: String)

    @Transaction
    suspend fun replaceMessageVisualRefs(
        messageId: Int,
        catalogEntries: List<VisualAssetCatalogEntity>,
        messageRefs: List<MessageVisualRefEntity>
    ) {
        deleteCatalogEntriesForMessage(messageId)
        deleteMessageRefs(messageId)
        if (catalogEntries.isNotEmpty()) {
            upsertCatalogEntries(catalogEntries)
        }
        if (messageRefs.isNotEmpty()) {
            upsertMessageRefs(messageRefs)
        }
    }
}
