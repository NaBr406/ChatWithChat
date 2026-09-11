package cn.nabr.chatwithchat.data.visual

import android.graphics.BitmapFactory
import cn.nabr.chatwithchat.data.database.dao.VisualAssetDao
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.MessageVisualRefEntity
import cn.nabr.chatwithchat.data.database.entity.VisualAssetCatalogEntity
import cn.nabr.chatwithchat.data.database.entity.VisualAssetEntity
import cn.nabr.chatwithchat.data.database.entity.VisualAssetProviderRefEntity
import cn.nabr.chatwithchat.data.model.ChatAttachment
import cn.nabr.chatwithchat.util.FileUtils
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class VisualAssetRepository @Inject constructor(
    private val dao: VisualAssetDao,
    private val store: VisualAssetStore,
    private val compressor: VisualAssetCompressor
) {
    private val indexingMutex = Mutex()
    private val compactionMutex = Mutex()

    suspend fun indexMessages(messages: List<MessageV2>) {
        if (messages.isEmpty()) return
        val ordered = messages.sortedWith(compareBy(MessageV2::createdAt, MessageV2::id))
        var turnOrdinal = 0
        ordered.forEach { message ->
            if (message.platformType == null) turnOrdinal += 1
            indexMessage(message, turnOrdinal.coerceAtLeast(1))
        }
    }

    suspend fun indexMessage(message: MessageV2, turnOrdinal: Int) {
        if (message.id <= 0 || message.chatId <= 0) return
        indexingMutex.withLock {
            val catalogEntries = mutableListOf<VisualAssetCatalogEntity>()
            val messageRefs = mutableListOf<MessageVisualRefEntity>()

            message.attachments.forEachIndexed { index, attachment ->
                val indexed = indexAttachment(
                    message = message,
                    attachmentIndex = index + 1,
                    turnOrdinal = turnOrdinal,
                    attachment = attachment
                ) ?: return@forEachIndexed
                catalogEntries += indexed.catalog
                messageRefs += indexed.ref
            }

            dao.replaceMessageVisualRefs(
                messageId = message.id,
                catalogEntries = catalogEntries,
                messageRefs = messageRefs
            )
        }
    }

    suspend fun resolve(
        chatId: Int,
        visualRef: String,
        detail: VisualDetail = VisualDetail.AUTO,
        maxBytes: Long = DEFAULT_MAX_READ_BYTES
    ): VisualAssetReadResult {
        if (chatId <= 0 || visualRef.isBlank()) {
            return VisualAssetReadResult.Failure(VisualAssetFailure.Forbidden)
        }
        val catalog = dao.getCatalogEntry(chatId, visualRef.trim())
            ?: return VisualAssetReadResult.Failure(VisualAssetFailure.NotFound)
        val asset = dao.getAsset(catalog.assetHash)
            ?: return VisualAssetReadResult.Failure(VisualAssetFailure.NotFound)

        val profile = when (detail) {
            VisualDetail.ORIGINAL -> VisualAssetProfile.ORIGINAL
            VisualDetail.AUTO,
            VisualDetail.COMPACT -> if (asset.hasCompactedProfile()) {
                VisualAssetProfile.COMPACTED_V1
            } else {
                VisualAssetProfile.ORIGINAL
            }
        }
        val resolved = when (profile) {
            VisualAssetProfile.ORIGINAL -> ResolvedCandidate(
                path = asset.originalPath,
                mimeType = asset.mimeType,
                width = asset.originalWidth,
                height = asset.originalHeight,
                sizeBytes = asset.originalSizeBytes
            )
            VisualAssetProfile.COMPACTED_V1 -> ResolvedCandidate(
                path = asset.compactedPath.orEmpty(),
                mimeType = asset.compactedMimeType ?: asset.mimeType,
                width = asset.compactedWidth,
                height = asset.compactedHeight,
                sizeBytes = asset.compactedSizeBytes ?: asset.originalSizeBytes
            )
        }
        if (resolved.path.isBlank() || resolved.sizeBytes <= 0L || resolved.sizeBytes > maxBytes) {
            return VisualAssetReadResult.Failure(
                if (resolved.sizeBytes > maxBytes) VisualAssetFailure.TooLarge else VisualAssetFailure.NotFound
            )
        }
        val file = File(resolved.path)
        if (!file.isFile) return VisualAssetReadResult.Failure(VisualAssetFailure.NotFound)
        if (profile == VisualAssetProfile.ORIGINAL && !store.verifyHash(resolved.path, asset.assetHash)) {
            return VisualAssetReadResult.Failure(VisualAssetFailure.Corrupt)
        }
        val bytes = store.readBytes(resolved.path)
            ?: return VisualAssetReadResult.Failure(VisualAssetFailure.NotFound)
        if (bytes.size.toLong() > maxBytes) {
            return VisualAssetReadResult.Failure(VisualAssetFailure.TooLarge)
        }
        return VisualAssetReadResult.Success(
            ResolvedVisualAsset(
                catalogItem = catalog.toModel(),
                asset = asset.toModel(),
                profile = profile,
                filePath = resolved.path,
                mimeType = resolved.mimeType,
                width = resolved.width,
                height = resolved.height,
                sizeBytes = bytes.size.toLong(),
                base64Data = Base64.getEncoder().encodeToString(bytes)
            )
        )
    }

    suspend fun listAssets(
        chatId: Int,
        query: String? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
        includeRaw: Boolean = true,
        includeFolded: Boolean = true
    ): List<VisualAssetCatalogItem> {
        if (chatId <= 0 || limit <= 0) return emptyList()
        val normalizedQuery = query?.trim().orEmpty()
        return dao.getCatalogEntries(chatId)
            .asSequence()
            .filter { entry ->
                when {
                    entry.foldedAt == null -> includeRaw
                    else -> includeFolded
                }
            }
            .filter { entry ->
                normalizedQuery.isBlank() ||
                    entry.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    entry.visualRef.contains(normalizedQuery, ignoreCase = true)
            }
            .take(limit.coerceAtMost(MAX_LIST_LIMIT))
            .map { entry -> entry.toModel() }
            .toList()
    }

    suspend fun listDiagnostics(): List<VisualAssetDiagnostic> {
        val assetsByHash = dao.getAllAssets().associateBy(VisualAssetEntity::assetHash)
        val providerRefsByAsset = dao.getAllProviderRefs().groupingBy { ref -> ref.assetHash }.eachCount()
        return dao.getAllCatalogEntries().mapNotNull { catalog ->
            val asset = assetsByHash[catalog.assetHash] ?: return@mapNotNull null
            VisualAssetDiagnostic(
                chatId = catalog.chatId,
                visualRef = catalog.visualRef,
                assetHash = catalog.assetHash,
                messageId = catalog.messageId,
                attachmentIndex = catalog.attachmentIndex,
                role = catalog.role,
                turnOrdinal = catalog.turnOrdinal,
                displayName = catalog.displayName,
                mimeType = catalog.mimeType,
                foldedAt = catalog.foldedAt,
                compactedProfile = catalog.compactedProfile,
                originalPath = asset.originalPath,
                originalExists = File(asset.originalPath).isFile,
                originalWidth = asset.originalWidth,
                originalHeight = asset.originalHeight,
                originalSizeBytes = asset.originalSizeBytes,
                compactedPath = asset.compactedPath,
                compactedExists = !asset.compactedPath.isNullOrBlank() && File(asset.compactedPath).isFile,
                compactedWidth = asset.compactedWidth,
                compactedHeight = asset.compactedHeight,
                compactedSizeBytes = asset.compactedSizeBytes,
                compactionError = asset.compactionError,
                providerRefCount = providerRefsByAsset[asset.assetHash] ?: 0
            )
        }
    }

    suspend fun markMessagesFolded(
        chatId: Int,
        messageIds: Collection<Int>,
        foldedAt: Long = System.currentTimeMillis() / 1000
    ) {
        val ids = messageIds.filter { it > 0 }.distinct()
        if (chatId <= 0 || ids.isEmpty()) return
        dao.markCatalogEntriesFolded(
            chatId = chatId,
            messageIds = ids,
            foldedAt = foldedAt,
            profile = VisualAssetProfile.COMPACTED_V1.storageValue
        )
        val assetHashes = ids.flatMap { messageId ->
            dao.getMessageRefs(messageId).map(MessageVisualRefEntity::assetHash)
        }.distinct()
        compressAssets(assetHashes)
    }

    suspend fun compressAssets(assetHashes: Collection<String>) {
        compactionMutex.withLock {
            assetHashes.filter(String::isNotBlank).distinct().forEach { assetHash ->
                val asset = dao.getAsset(assetHash) ?: return@forEach
                if (asset.hasCompactedProfile() && File(asset.compactedPath.orEmpty()).isFile) return@forEach
                val result = compressor.compress(
                    assetHash = asset.assetHash,
                    sourcePath = asset.originalPath,
                    sourceMimeType = asset.mimeType,
                    sourceSizeBytes = asset.originalSizeBytes,
                    sourceWidth = asset.originalWidth,
                    sourceHeight = asset.originalHeight
                )
                dao.upsertAsset(
                    result.fold(
                        onSuccess = { compressed ->
                            asset.copy(
                                compactedPath = compressed.filePath,
                                compactedMimeType = compressed.mimeType,
                                compactedWidth = compressed.width,
                                compactedHeight = compressed.height,
                                compactedSizeBytes = compressed.sizeBytes,
                                compactedVersion = compressed.version,
                                compactionError = null,
                                updatedAt = System.currentTimeMillis() / 1000
                            )
                        },
                        onFailure = { throwable ->
                            asset.copy(
                                compactionError = throwable.message ?: "visual_asset_compaction_failed",
                                updatedAt = System.currentTimeMillis() / 1000
                            )
                        }
                    )
                )
            }
        }
    }

    suspend fun deleteUnreferencedAssets() {
        dao.getAllAssets().forEach { asset ->
            if (dao.countMessageRefsForAsset(asset.assetHash) > 0) return@forEach
            if (asset.compactedPath != asset.originalPath) {
                store.deleteIfExists(asset.compactedPath)
            }
            store.deleteIfExists(asset.originalPath)
            dao.deleteProviderRefsForAsset(asset.assetHash)
            dao.deleteAssetIfUnreferenced(asset.assetHash)
        }
    }

    suspend fun backfillCatalog() {
        // Lazy indexing is intentionally message-driven. This method exists as the startup hook for
        // removing files whose owning message rows disappeared while the process was dead.
        deleteUnreferencedAssets()
    }

    private suspend fun indexAttachment(
        message: MessageV2,
        attachmentIndex: Int,
        turnOrdinal: Int,
        attachment: ChatAttachment
    ): IndexedAttachment? {
        val path = attachment.preparedFilePath.ifBlank { attachment.localFilePath }
        if (path.isBlank()) return null
        val mimeType = attachment.mimeType.ifBlank { FileUtils.getMimeTypeFromPath(path) }
        if (!FileUtils.isImage(mimeType)) return null
        val sizeBytes = File(path).takeIf(File::isFile)?.length()
            ?: attachment.sizeBytes.takeIf { it > 0L }
            ?: return null
        val assetHash = store.sha256(path) ?: return null
        val storedPath = store.ensureOriginalStored(assetHash, mimeType, path) ?: return null
        val dimensions = readDimensions(storedPath)
        val now = System.currentTimeMillis() / 1000
        val existing = dao.getAsset(assetHash)
        dao.upsertAsset(
            VisualAssetEntity(
                assetHash = assetHash,
                mimeType = mimeType,
                originalPath = storedPath,
                originalWidth = dimensions?.first ?: attachment.width,
                originalHeight = dimensions?.second ?: attachment.height,
                originalSizeBytes = sizeBytes,
                compactedPath = existing?.compactedPath,
                compactedMimeType = existing?.compactedMimeType,
                compactedWidth = existing?.compactedWidth,
                compactedHeight = existing?.compactedHeight,
                compactedSizeBytes = existing?.compactedSizeBytes,
                compactedVersion = existing?.compactedVersion,
                compactionError = existing?.compactionError,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
        attachment.providerRefs.forEach { providerRef ->
            dao.upsertProviderRef(
                VisualAssetProviderRefEntity(
                    assetHash = assetHash,
                    profile = VisualAssetProfile.ORIGINAL.storageValue,
                    platformUid = providerRef.platformUid,
                    remoteType = providerRef.remoteType.name,
                    remoteId = providerRef.remoteId,
                    remoteName = providerRef.remoteName,
                    mimeType = providerRef.mimeType,
                    updatedAt = now
                )
            )
        }

        val visualRef = visualRef(message.id, attachmentIndex)
        return IndexedAttachment(
            catalog = VisualAssetCatalogEntity(
                chatId = message.chatId,
                visualRef = visualRef,
                assetHash = assetHash,
                messageId = message.id,
                attachmentIndex = attachmentIndex,
                role = if (message.platformType == null) ROLE_USER else ROLE_ASSISTANT,
                turnOrdinal = turnOrdinal,
                displayName = attachment.resolvedDisplayName,
                mimeType = mimeType,
                width = dimensions?.first ?: attachment.width,
                height = dimensions?.second ?: attachment.height,
                createdAt = message.createdAt
            ),
            ref = MessageVisualRefEntity(
                messageId = message.id,
                attachmentIndex = attachmentIndex,
                assetHash = assetHash,
                visualRef = visualRef
            )
        )
    }

    private fun readDimensions(path: String): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(path).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            null
        } else {
            options.outWidth to options.outHeight
        }
    }.getOrNull()

    private data class IndexedAttachment(
        val catalog: VisualAssetCatalogEntity,
        val ref: MessageVisualRefEntity
    )

    private data class ResolvedCandidate(
        val path: String,
        val mimeType: String,
        val width: Int?,
        val height: Int?,
        val sizeBytes: Long
    )

    companion object {
        fun visualRef(messageId: Int, attachmentIndex: Int): String = "v$messageId-$attachmentIndex"

        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val DEFAULT_LIST_LIMIT = 50
        private const val MAX_LIST_LIMIT = 200
        private const val DEFAULT_MAX_READ_BYTES = 20L * 1024L * 1024L
    }
}

private fun VisualAssetEntity.toModel(): VisualAsset = VisualAsset(
    assetHash = assetHash,
    mimeType = mimeType,
    originalPath = originalPath,
    originalWidth = originalWidth,
    originalHeight = originalHeight,
    originalSizeBytes = originalSizeBytes,
    compactedPath = compactedPath,
    compactedMimeType = compactedMimeType,
    compactedWidth = compactedWidth,
    compactedHeight = compactedHeight,
    compactedSizeBytes = compactedSizeBytes,
    compactedVersion = compactedVersion,
    compactionError = compactionError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun VisualAssetEntity.hasCompactedProfile(): Boolean =
    compactedVersion == VisualAssetProfile.COMPACTED_V1.version && !compactedPath.isNullOrBlank()

private fun VisualAssetCatalogEntity.toModel(): VisualAssetCatalogItem = VisualAssetCatalogItem(
    chatId = chatId,
    visualRef = visualRef,
    assetHash = assetHash,
    messageId = messageId,
    attachmentIndex = attachmentIndex,
    role = role,
    turnOrdinal = turnOrdinal,
    displayName = displayName,
    mimeType = mimeType,
    width = width,
    height = height,
    createdAt = createdAt,
    foldedAt = foldedAt,
    compactedProfile = compactedProfile
)
