package cn.nabr.chatwithchat.data.sticker

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.StickerCatalogDao
import cn.nabr.chatwithchat.data.database.entity.StickerAssetEntity
import cn.nabr.chatwithchat.data.database.entity.StickerItemEntity
import cn.nabr.chatwithchat.data.database.entity.StickerPackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class BundledStickerCatalogSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ChatDatabaseV2,
    private val stickerCatalogDao: StickerCatalogDao,
    private val assetStore: StickerAssetStore
) {
    private val mutex = Mutex()
    private var seeded = false

    suspend fun ensureSeeded() {
        mutex.withLock {
            if (seeded) return
            runCatching {
                val manifest = loadManifest() ?: return@runCatching
                val timestamp = System.currentTimeMillis() / 1000
                val validItems = manifest.items.mapNotNull { item ->
                    validateItem(item)?.toEntities(manifest.packId, timestamp)
                }
                database.withTransaction {
                    stickerCatalogDao.upsertPack(
                        StickerPackEntity(
                            packId = manifest.packId,
                            displayName = manifest.displayName,
                            isBuiltin = true,
                            createdAt = timestamp,
                            updatedAt = timestamp
                        )
                    )
                    validItems.forEach { (asset, item) ->
                        stickerCatalogDao.upsertAsset(asset)
                        stickerCatalogDao.upsertItem(item)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Unable to seed bundled stickers: ${error.message.orEmpty().take(MAX_LOG_MESSAGE_LENGTH)}")
            }
            seeded = true
        }
    }

    private fun loadManifest(): BundledStickerManifest? = runCatching {
        val manifestText = context.assets.open(BUNDLED_MANIFEST_PATH).bufferedReader().use { reader -> reader.readText() }
        json.decodeFromString<BundledStickerManifest>(manifestText)
            .takeIf { manifest ->
                manifest.version == MANIFEST_VERSION &&
                    manifest.packId == STICKER_PACK_ID_BUILTIN_REACTIONS &&
                    manifest.displayName.isNotBlank()
            }
    }.onFailure { error ->
        Log.w(TAG, "Unable to read bundled sticker manifest: ${error.message.orEmpty().take(MAX_LOG_MESSAGE_LENGTH)}")
    }.getOrNull()

    private fun validateItem(item: BundledStickerItem): ValidBundledSticker? = runCatching {
        require(item.stickerId.startsWith("$STICKER_PACK_ID_BUILTIN_REACTIONS."))
        require(item.assetPath.startsWith(BUNDLED_ASSET_ROOT))
        require(item.assetPath.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." })
        require(ASSET_KEY_REGEX.matches(item.sha256))
        require(item.mediaKind == STICKER_MEDIA_KIND_STATIC_RASTER)
        require(item.mimeType in SUPPORTED_BUNDLED_MIME_TYPES)
        require(item.byteSize > 0L && item.width > 0 && item.height > 0)
        val metadata = StickerMetadataCodec.normalize(
            StickerItemMetadata(item.title, item.altText, item.tags, item.aliases)
        ) ?: error("Invalid bundled sticker metadata")
        val asset = StickerAssetEntity(
            assetKey = item.sha256,
            storageKind = STICKER_STORAGE_KIND_BUNDLED,
            relativePath = item.assetPath,
            mediaKind = item.mediaKind,
            mimeType = item.mimeType,
            posterAssetKey = item.posterAssetKey,
            durationMs = item.durationMs,
            loopCount = item.loopCount,
            byteSize = item.byteSize,
            width = item.width,
            height = item.height
        )
        require(assetStore.verify(asset))
        ValidBundledSticker(item.stickerId, asset, metadata)
    }.onFailure {
        Log.w(TAG, "Skipping invalid bundled sticker ${item.stickerId.take(MAX_STICKER_ID_LOG_LENGTH)}")
    }.getOrNull()

    private fun ValidBundledSticker.toEntities(packId: String, timestamp: Long): Pair<StickerAssetEntity, StickerItemEntity> =
        asset to StickerItemEntity(
            stickerId = stickerId,
            packId = packId,
            assetKey = asset.assetKey,
            title = metadata.title,
            altText = metadata.altText,
            tagsJson = StickerMetadataCodec.encodeTags(metadata.tags),
            aliasesJson = StickerMetadataCodec.encodeTags(metadata.aliases),
            enabled = true,
            isBuiltin = true,
            createdAt = timestamp,
            updatedAt = timestamp
        )

    private data class ValidBundledSticker(
        val stickerId: String,
        val asset: StickerAssetEntity,
        val metadata: StickerItemMetadata
    )

    @Serializable
    private data class BundledStickerManifest(
        val version: Int,
        val packId: String,
        val displayName: String,
        val items: List<BundledStickerItem>
    )

    @Serializable
    private data class BundledStickerItem(
        val stickerId: String,
        val assetPath: String,
        val sha256: String,
        val mimeType: String,
        val mediaKind: String,
        val title: String,
        val altText: String,
        val tags: List<String>,
        val aliases: List<String> = emptyList(),
        val byteSize: Long,
        val width: Int,
        val height: Int,
        val posterAssetKey: String? = null,
        val durationMs: Long? = null,
        val loopCount: Int? = null
    )

    private companion object {
        const val TAG = "StickerSeeder"
        const val BUNDLED_MANIFEST_PATH = "stickers/builtin.reactions/v1/manifest.json"
        const val BUNDLED_ASSET_ROOT = "stickers/builtin.reactions/v1/"
        const val MANIFEST_VERSION = 1
        const val MAX_LOG_MESSAGE_LENGTH = 160
        const val MAX_STICKER_ID_LOG_LENGTH = 96
        val ASSET_KEY_REGEX = Regex("[a-f0-9]{64}")
        val SUPPORTED_BUNDLED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
