package cn.nabr.chatwithchat.data.sticker

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface StickerRepository : StickerAssetOpener {
    fun observeCatalog(): Flow<List<StickerCatalogItem>>

    suspend fun ensureInitialized()

    suspend fun importStaticImages(uris: List<Uri>): StickerImportBatchResult

    suspend fun updateCustomItem(stickerId: String, metadata: StickerItemMetadata): Boolean

    suspend fun setCustomItemEnabled(stickerId: String, enabled: Boolean): Boolean

    suspend fun deleteCustomItem(stickerId: String): Boolean

    suspend fun searchEnabledStatic(query: String, limit: Int = 6): List<StickerSearchCandidate>

    suspend fun resolveEnabledStatic(stickerId: String, instanceId: String): StickerResolution

    override suspend fun openAsset(assetKey: String): InputStream?
}
