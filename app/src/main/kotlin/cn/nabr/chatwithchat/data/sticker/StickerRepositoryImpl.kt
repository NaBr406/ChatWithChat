package cn.nabr.chatwithchat.data.sticker

import android.net.Uri
import androidx.room.withTransaction
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.dao.StickerCatalogDao
import cn.nabr.chatwithchat.data.database.dao.StickerItemWithAsset
import cn.nabr.chatwithchat.data.database.entity.StickerAssetEntity
import cn.nabr.chatwithchat.data.database.entity.StickerItemEntity
import cn.nabr.chatwithchat.data.database.entity.StickerPackEntity
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

@Singleton
class StickerRepositoryImpl @Inject constructor(
    private val database: ChatDatabaseV2,
    private val stickerCatalogDao: StickerCatalogDao,
    private val messageV2Dao: MessageV2Dao,
    private val bundledStickerCatalogSeeder: BundledStickerCatalogSeeder,
    private val stickerImportService: StickerImportService,
    private val assetStore: StickerAssetStore
) : StickerRepository {
    override fun observeCatalog(): Flow<List<StickerCatalogItem>> = flow {
        ensureInitialized()
        emitAll(
            stickerCatalogDao.observeItemsWithAssets().map { items ->
                items.mapNotNull { item -> item.toCatalogItem() }
            }
        )
    }

    override suspend fun ensureInitialized() {
        withContext(Dispatchers.IO) {
            bundledStickerCatalogSeeder.ensureSeeded()
        }
    }

    override suspend fun importStaticImages(uris: List<Uri>): StickerImportBatchResult {
        ensureInitialized()
        val imported = mutableListOf<StickerCatalogItem>()
        val rejected = mutableListOf<StickerImportRejected>()

        uris.distinct().forEach { uri ->
            when (val preparation = stickerImportService.prepare(uri)) {
                is StickerImportPreparation.Rejected -> {
                    rejected += StickerImportRejected(uri, preparation.reason)
                }

                is StickerImportPreparation.Ready -> {
                    val importedItem = withContext(Dispatchers.IO) {
                        persistPreparedImport(preparation.prepared)
                    }
                    if (importedItem == null) {
                        rejected += StickerImportRejected(uri, StickerImportFailure.STORAGE_FAILED)
                    } else {
                        imported += importedItem
                    }
                }
            }
        }

        return StickerImportBatchResult(imported = imported, rejected = rejected)
    }

    override suspend fun updateCustomItem(stickerId: String, metadata: StickerItemMetadata): Boolean {
        ensureInitialized()
        val normalized = StickerMetadataCodec.normalize(metadata) ?: return false
        return stickerCatalogDao.updateCustomItemMetadata(
            stickerId = stickerId,
            title = normalized.title,
            altText = normalized.altText,
            tagsJson = StickerMetadataCodec.encodeTags(normalized.tags),
            updatedAt = nowSeconds()
        ) == 1
    }

    override suspend fun setCustomItemEnabled(stickerId: String, enabled: Boolean): Boolean {
        ensureInitialized()
        return stickerCatalogDao.updateCustomItemEnabled(
            stickerId = stickerId,
            enabled = enabled,
            updatedAt = nowSeconds()
        ) == 1
    }

    override suspend fun deleteCustomItem(stickerId: String): Boolean {
        ensureInitialized()
        val item = stickerCatalogDao.getItem(stickerId) ?: return false
        if (item.isBuiltin) return false
        val asset = stickerCatalogDao.getAsset(item.assetKey)

        val reclaimableAsset = database.withTransaction {
            stickerCatalogDao.deleteItem(item)
            asset?.takeIf { candidate ->
                stickerCatalogDao.countItemsForAsset(candidate.assetKey) == 0 &&
                    messageV2Dao.countStickerAssetReferences(candidate.assetKey) == 0
            }
        }

        if (reclaimableAsset != null && assetStore.deleteLocal(reclaimableAsset)) {
            database.withTransaction {
                if (
                    stickerCatalogDao.countItemsForAsset(reclaimableAsset.assetKey) == 0 &&
                    messageV2Dao.countStickerAssetReferences(reclaimableAsset.assetKey) == 0
                ) {
                    stickerCatalogDao.deleteAsset(reclaimableAsset)
                }
            }
        }
        return true
    }

    override suspend fun searchEnabledStatic(query: String, limit: Int): List<StickerSearchCandidate> {
        ensureInitialized()
        val normalizedQuery = query.normalizedStickerSearchText()
        if (normalizedQuery.isBlank()) return emptyList()
        val tokens = normalizedQuery.split(SEARCH_SEPARATOR).filter(String::isNotBlank)
        if (tokens.isEmpty()) return emptyList()

        return stickerCatalogDao.getEnabledItemsWithAssets()
            .mapNotNull { item -> item.toCatalogItem() }
            .filter { item -> item.asset.mediaKind == STICKER_MEDIA_KIND_STATIC_RASTER }
            .map { item -> item to item.stickerMatchScore(normalizedQuery, tokens) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<StickerCatalogItem, Int>> { (_, score) -> score }
                    .thenBy { (item, _) -> item.title }
                    .thenBy { (item, _) -> item.stickerId }
            )
            .take(limit.coerceIn(1, MAX_SEARCH_RESULTS))
            .map { (item, _) ->
                StickerSearchCandidate(
                    stickerId = item.stickerId,
                    title = item.title,
                    altText = item.altText,
                    tags = item.tags
                )
            }
    }

    override suspend fun resolveEnabledStatic(stickerId: String, instanceId: String): StickerResolution {
        ensureInitialized()
        if (stickerId.isBlank() || stickerId.length > MAX_STICKER_ID_LENGTH) {
            return StickerResolution.Unavailable(STICKER_NOT_FOUND)
        }
        if (instanceId.isBlank() || instanceId.length > MAX_INSTANCE_ID_LENGTH) {
            return StickerResolution.Unavailable(STICKER_UNAVAILABLE)
        }
        val item = stickerCatalogDao.getItemWithAsset(stickerId)
            ?: return StickerResolution.Unavailable(STICKER_NOT_FOUND)
        val catalogItem = item.toCatalogItem() ?: return StickerResolution.Unavailable(STICKER_UNAVAILABLE)
        if (!catalogItem.enabled || catalogItem.asset.mediaKind != STICKER_MEDIA_KIND_STATIC_RASTER) {
            return StickerResolution.Unavailable(STICKER_UNAVAILABLE)
        }
        val asset = item.asset ?: return StickerResolution.Unavailable(STICKER_UNAVAILABLE)
        val assetIsAvailable = withContext(Dispatchers.IO) { assetStore.verify(asset) }
        return if (assetIsAvailable) {
            StickerResolution.Success(
                StickerPresentationArtifact(
                    instanceId = instanceId,
                    stickerId = catalogItem.stickerId,
                    assetKey = catalogItem.asset.assetKey,
                    altText = catalogItem.altText,
                    mediaKind = catalogItem.asset.mediaKind
                )
            )
        } else {
            StickerResolution.Unavailable(STICKER_UNAVAILABLE)
        }
    }

    override suspend fun openAsset(assetKey: String): InputStream? {
        ensureInitialized()
        if (!ASSET_KEY_REGEX.matches(assetKey)) return null
        val asset = stickerCatalogDao.getAsset(assetKey) ?: return null
        return withContext(Dispatchers.IO) { assetStore.open(asset) }
    }

    private suspend fun persistPreparedImport(prepared: PreparedStickerImport): StickerCatalogItem? {
        val existingAsset = stickerCatalogDao.getAsset(prepared.assetKey)
        val usableExistingAsset = existingAsset?.takeIf(assetStore::verify)
        var createdLocalAsset = false
        val asset = usableExistingAsset ?: run {
            val stored = assetStore.promote(prepared.stagingFile, prepared.assetKey, prepared.extension) ?: return null
            createdLocalAsset = stored.wasCreated
            StickerAssetEntity(
                assetKey = prepared.assetKey,
                storageKind = STICKER_STORAGE_KIND_LOCAL,
                relativePath = stored.relativePath,
                mediaKind = STICKER_MEDIA_KIND_STATIC_RASTER,
                mimeType = prepared.mimeType,
                byteSize = prepared.byteSize,
                width = prepared.width,
                height = prepared.height
            )
        }
        if (usableExistingAsset != null) assetStore.deleteStagingFile(prepared.stagingFile)

        val stickerId = generateUserStickerId()
        val timestamp = nowSeconds()
        val item = StickerItemEntity(
            stickerId = stickerId,
            packId = STICKER_PACK_ID_USER,
            assetKey = asset.assetKey,
            title = prepared.metadata.title,
            altText = prepared.metadata.altText,
            tagsJson = StickerMetadataCodec.encodeTags(prepared.metadata.tags),
            aliasesJson = StickerMetadataCodec.encodeTags(prepared.metadata.aliases),
            enabled = true,
            isBuiltin = false,
            createdAt = timestamp,
            updatedAt = timestamp
        )

        return try {
            database.withTransaction {
                stickerCatalogDao.upsertPack(
                    StickerPackEntity(
                        packId = STICKER_PACK_ID_USER,
                        displayName = "我的表情",
                        isBuiltin = false,
                        createdAt = timestamp,
                        updatedAt = timestamp
                    )
                )
                if (usableExistingAsset == null) stickerCatalogDao.upsertAsset(asset)
                stickerCatalogDao.upsertItem(item)
            }
            StickerItemWithAsset(item = item, asset = asset).toCatalogItem()
        } catch (_: Exception) {
            if (createdLocalAsset) assetStore.deleteLocal(asset)
            null
        }
    }

    private suspend fun generateUserStickerId(): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val stickerId = "$USER_STICKER_ID_PREFIX${UUID.randomUUID()}"
            if (stickerCatalogDao.getItem(stickerId) == null) return stickerId
        }
        error("Unable to allocate a unique sticker ID")
    }

    private fun StickerItemWithAsset.toCatalogItem(): StickerCatalogItem? {
        val asset = asset ?: return null
        val metadata = StickerMetadataCodec.normalize(
            StickerItemMetadata(
                title = item.title,
                altText = item.altText,
                tags = StickerMetadataCodec.decodeTags(item.tagsJson),
                aliases = StickerMetadataCodec.decodeTags(item.aliasesJson)
            )
        ) ?: return null
        return StickerCatalogItem(
            stickerId = item.stickerId,
            packId = item.packId,
            title = metadata.title,
            altText = metadata.altText,
            tags = metadata.tags,
            aliases = metadata.aliases,
            enabled = item.enabled,
            isBuiltin = item.isBuiltin,
            asset = StickerAssetDescriptor(
                assetKey = asset.assetKey,
                mediaKind = asset.mediaKind,
                mimeType = asset.mimeType,
                posterAssetKey = asset.posterAssetKey,
                durationMs = asset.durationMs,
                loopCount = asset.loopCount
            ),
            updatedAt = item.updatedAt
        )
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val MAX_SEARCH_RESULTS = 6
        const val MAX_STICKER_ID_LENGTH = 180
        const val MAX_INSTANCE_ID_LENGTH = 240
        const val MAX_ID_GENERATION_ATTEMPTS = 3
        const val STICKER_NOT_FOUND = "sticker_not_found"
        const val STICKER_UNAVAILABLE = "sticker_unavailable"
        val ASSET_KEY_REGEX = Regex("[a-f0-9]{64}")
        val SEARCH_SEPARATOR = Regex("[\\s,，;；、]+")
    }
}

internal fun StickerCatalogItem.stickerMatchScore(query: String, tokens: List<String>): Int {
    val normalizedTitle = title.normalizedStickerSearchText()
    val normalizedAltText = altText.normalizedStickerSearchText()
    val normalizedTags = tags.map { tag -> tag.normalizedStickerSearchText() }
    val normalizedAliases = aliases.map { alias -> alias.normalizedStickerSearchText() }
    val semanticTerms = normalizedTags + normalizedAliases
    val fields = listOf(normalizedTitle, normalizedAltText) + semanticTerms
    return tokens.sumOf { token ->
        when {
            semanticTerms.any { term -> term == token } || normalizedTitle == token -> 12
            fields.any { field -> field.startsWith(token) } -> 6
            fields.any { field -> field.contains(token) } -> 2
            query == token && fields.any { field -> field.contains(query) } -> 1
            else -> 0
        }
    }
}

private fun String.normalizedStickerSearchText(): String = trim().lowercase(Locale.ROOT)
