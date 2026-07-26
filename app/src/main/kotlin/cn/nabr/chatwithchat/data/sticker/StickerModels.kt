package cn.nabr.chatwithchat.data.sticker

import android.net.Uri
import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
import cn.nabr.chatwithchat.data.tool.ToolPresentationArtifact
import kotlinx.serialization.Serializable
import java.io.InputStream

const val STICKER_MEDIA_KIND_STATIC_RASTER = "static_raster"
const val STICKER_PACK_ID_BUILTIN_REACTIONS = "builtin.reactions"
const val STICKER_PACK_ID_USER = "user.my_stickers"
const val STICKER_STORAGE_KIND_BUNDLED = "bundled_asset"
const val STICKER_STORAGE_KIND_LOCAL = "local_file"
const val USER_STICKER_ID_PREFIX = "user."

@Serializable
data class StickerAssetDescriptor(
    val assetKey: String,
    val mediaKind: String,
    val mimeType: String,
    val posterAssetKey: String? = null,
    val durationMs: Long? = null,
    val loopCount: Int? = null
)

data class StickerCatalogItem(
    val stickerId: String,
    val packId: String,
    val title: String,
    val altText: String,
    val tags: List<String>,
    val enabled: Boolean,
    val isBuiltin: Boolean,
    val asset: StickerAssetDescriptor,
    val updatedAt: Long,
    val aliases: List<String> = emptyList()
)

data class StickerSearchCandidate(
    val stickerId: String,
    val title: String,
    val altText: String,
    val tags: List<String>
)

data class StickerPresentationArtifact(
    override val instanceId: String,
    val stickerId: String,
    val assetKey: String,
    val altText: String,
    val mediaKind: String = STICKER_MEDIA_KIND_STATIC_RASTER
) : ToolPresentationArtifact

fun StickerPresentationArtifact.toMessageStickerRef(): MessageStickerRef = MessageStickerRef(
    instanceId = instanceId,
    stickerId = stickerId,
    assetKey = assetKey,
    altText = altText,
    mediaKind = mediaKind
)

data class StickerItemMetadata(
    val title: String,
    val altText: String,
    val tags: List<String>,
    val aliases: List<String> = emptyList()
)

sealed interface StickerResolution {
    data class Success(val artifact: StickerPresentationArtifact) : StickerResolution

    data class Unavailable(val code: String) : StickerResolution
}

data class StickerImportBatchResult(
    val imported: List<StickerCatalogItem>,
    val rejected: List<StickerImportRejected>
)

data class StickerImportRejected(
    val uri: Uri,
    val reason: StickerImportFailure
)

enum class StickerImportFailure {
    EMPTY_INPUT,
    INPUT_TOO_LARGE,
    UNSUPPORTED_MEDIA,
    ANIMATED_MEDIA,
    MALFORMED_IMAGE,
    IMAGE_TOO_LARGE,
    NORMALIZATION_FAILED,
    STORAGE_FAILED
}

internal data class PreparedStickerImport(
    val sourceUri: Uri,
    val stagingFile: java.io.File,
    val assetKey: String,
    val mimeType: String,
    val extension: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val metadata: StickerItemMetadata
)

interface StickerAssetOpener {
    suspend fun openAsset(assetKey: String): InputStream?
}
