package cn.nabr.chatwithchat.data.visual

import kotlinx.serialization.Serializable

@Serializable
enum class VisualAssetProfile(val storageValue: String, val version: Int) {
    ORIGINAL("ORIGINAL", 0),
    COMPACTED_V1("COMPACTED_V1", 1);

    companion object {
        fun fromStorage(value: String?): VisualAssetProfile =
            entries.firstOrNull { profile -> profile.storageValue == value } ?: ORIGINAL
    }
}

@Serializable
enum class VisualDetail(val wireValue: String) {
    AUTO("auto"),
    COMPACT("compact"),
    ORIGINAL("original");

    companion object {
        fun fromWire(value: String?): VisualDetail =
            entries.firstOrNull { detail -> detail.wireValue == value?.trim()?.lowercase() } ?: AUTO
    }
}

data class VisualAsset(
    val assetHash: String,
    val mimeType: String,
    val originalPath: String,
    val originalWidth: Int?,
    val originalHeight: Int?,
    val originalSizeBytes: Long,
    val compactedPath: String?,
    val compactedMimeType: String?,
    val compactedWidth: Int?,
    val compactedHeight: Int?,
    val compactedSizeBytes: Long?,
    val compactedVersion: Int?,
    val compactionError: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun hasCompactedProfile(): Boolean =
        compactedVersion == VisualAssetProfile.COMPACTED_V1.version && !compactedPath.isNullOrBlank()
}

data class VisualAssetCatalogItem(
    val chatId: Int,
    val visualRef: String,
    val assetHash: String,
    val messageId: Int,
    val attachmentIndex: Int,
    val role: String,
    val turnOrdinal: Int,
    val displayName: String,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val createdAt: Long,
    val foldedAt: Long?,
    val compactedProfile: String?
) {
    val isFolded: Boolean
        get() = foldedAt != null
}

data class ResolvedVisualAsset(
    val catalogItem: VisualAssetCatalogItem,
    val asset: VisualAsset,
    val profile: VisualAssetProfile,
    val filePath: String,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long,
    val base64Data: String
)

data class VisualAssetCompressionResult(
    val filePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val version: Int = VisualAssetProfile.COMPACTED_V1.version
)

sealed interface VisualAssetFailure {
    val code: String

    data object NotFound : VisualAssetFailure {
        override val code: String = "visual_asset_not_found"
    }

    data object Forbidden : VisualAssetFailure {
        override val code: String = "visual_asset_forbidden"
    }

    data object Corrupt : VisualAssetFailure {
        override val code: String = "visual_asset_corrupt"
    }

    data object TooLarge : VisualAssetFailure {
        override val code: String = "visual_asset_too_large"
    }

    data class Unsupported(val reason: String = "visual_asset_unsupported") : VisualAssetFailure {
        override val code: String = reason
    }
}

sealed interface VisualAssetReadResult {
    data class Success(val asset: ResolvedVisualAsset) : VisualAssetReadResult

    data class Failure(val failure: VisualAssetFailure) : VisualAssetReadResult
}
