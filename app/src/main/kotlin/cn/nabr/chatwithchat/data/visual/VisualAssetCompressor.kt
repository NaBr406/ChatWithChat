package cn.nabr.chatwithchat.data.visual

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisualAssetCompressor @Inject constructor(
    private val store: VisualAssetStore
) {
    fun compress(
        assetHash: String,
        sourcePath: String,
        sourceMimeType: String,
        sourceSizeBytes: Long,
        sourceWidth: Int?,
        sourceHeight: Int?
    ): Result<VisualAssetCompressionResult> = runCatching {
        if (
            sourceSizeBytes <= REUSE_ORIGINAL_MAX_BYTES &&
            maxOf(sourceWidth ?: 0, sourceHeight ?: 0) <= MAX_LONG_EDGE_PIXELS
        ) {
            return@runCatching VisualAssetCompressionResult(
                filePath = sourcePath,
                mimeType = sourceMimeType,
                width = sourceWidth ?: 0,
                height = sourceHeight ?: 0,
                sizeBytes = sourceSizeBytes
            )
        }

        val source = ImageDecoder.createSource(File(sourcePath))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longestEdge = maxOf(width, height)
            if (longestEdge > MAX_LONG_EDGE_PIXELS) {
                val scale = MAX_LONG_EDGE_PIXELS.toDouble() / longestEdge.toDouble()
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1)
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

        try {
            val hasAlpha = bitmap.hasAlpha()
            val destination = store.compactionPath(assetHash, "webp")
            val temporary = store.temporaryCompactionPath(assetHash, "webp")
            val firstFormat = if (hasAlpha) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            val firstQuality = if (hasAlpha) 100 else COMPRESSED_QUALITY
            val firstMimeType = "image/webp"
            encode(bitmap, temporary, firstFormat, firstQuality)
            if (!store.replaceAtomically(temporary, destination)) {
                throw IllegalStateException("visual_asset_compaction_atomic_replace_failed")
            }

            val alphaFallback = hasAlpha && File(destination).length() > LOSSLESS_PNG_FALLBACK_BYTES
            val finalPath: String
            val finalMimeType: String
            if (alphaFallback) {
                val pngPath = store.compactionPath(assetHash, "png")
                val pngTemporary = store.temporaryCompactionPath(assetHash, "png")
                encode(bitmap, pngTemporary, Bitmap.CompressFormat.PNG, 100)
                if (!store.replaceAtomically(pngTemporary, pngPath)) {
                    throw IllegalStateException("visual_asset_compaction_png_fallback_failed")
                }
                finalPath = pngPath
                finalMimeType = "image/png"
            } else {
                finalPath = destination
                finalMimeType = firstMimeType
            }

            VisualAssetCompressionResult(
                filePath = finalPath,
                mimeType = finalMimeType,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = File(finalPath).length()
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun encode(
        bitmap: Bitmap,
        path: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ) {
        FileOutputStream(path, false).use { output ->
            if (!bitmap.compress(format, quality, output)) {
                throw IllegalStateException("visual_asset_compaction_encode_failed")
            }
            output.fd.sync()
        }
    }

    private companion object {
        const val MAX_LONG_EDGE_PIXELS = 2048
        const val COMPRESSED_QUALITY = 82
        const val REUSE_ORIGINAL_MAX_BYTES = 2L * 1024L * 1024L
        const val LOSSLESS_PNG_FALLBACK_BYTES = 8L * 1024L * 1024L
    }
}
