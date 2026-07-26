package cn.nabr.chatwithchat.data.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface StickerImportPreparation {
    data class Ready(val prepared: PreparedStickerImport) : StickerImportPreparation

    data class Rejected(val reason: StickerImportFailure) : StickerImportPreparation
}

@Singleton
class StickerImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assetStore: StickerAssetStore
) {
    internal suspend fun prepare(uri: Uri): StickerImportPreparation = withContext(Dispatchers.IO) {
        val sourceFile = assetStore.createStagingFile()
            ?: return@withContext StickerImportPreparation.Rejected(StickerImportFailure.STORAGE_FAILED)
        var normalizedFile: File? = null

        try {
            when (copyIntoStaging(uri, sourceFile)) {
                CopyResult.Empty -> return@withContext StickerImportPreparation.Rejected(StickerImportFailure.EMPTY_INPUT)
                CopyResult.TooLarge -> return@withContext StickerImportPreparation.Rejected(StickerImportFailure.INPUT_TOO_LARGE)
                CopyResult.Failed -> return@withContext StickerImportPreparation.Rejected(StickerImportFailure.MALFORMED_IMAGE)
                CopyResult.Copied -> Unit
            }

            val inputBytes = sourceFile.readBytes()
            val format = detectFormat(inputBytes)
                ?: return@withContext StickerImportPreparation.Rejected(StickerImportFailure.UNSUPPORTED_MEDIA)
            if (format.isAnimated(inputBytes)) {
                return@withContext StickerImportPreparation.Rejected(StickerImportFailure.ANIMATED_MEDIA)
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                return@withContext StickerImportPreparation.Rejected(StickerImportFailure.MALFORMED_IMAGE)
            }
            if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION || width.toLong() * height > MAX_IMAGE_PIXELS) {
                return@withContext StickerImportPreparation.Rejected(StickerImportFailure.IMAGE_TOO_LARGE)
            }

            val decoded = BitmapFactory.decodeFile(
                sourceFile.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ) ?: return@withContext StickerImportPreparation.Rejected(StickerImportFailure.MALFORMED_IMAGE)
            val normalizedBitmap = orientBitmap(decoded, sourceFile, format)
            val outputFile = assetStore.createStagingFile()
                ?: return@withContext StickerImportPreparation.Rejected(StickerImportFailure.STORAGE_FAILED)
            normalizedFile = outputFile

            try {
                outputFile.outputStream().buffered().use { output ->
                    if (!normalizedBitmap.compress(format.compressFormat, format.quality, output)) {
                        return@withContext StickerImportPreparation.Rejected(StickerImportFailure.NORMALIZATION_FAILED)
                    }
                }
            } finally {
                if (normalizedBitmap !== decoded) normalizedBitmap.recycle()
                decoded.recycle()
            }

            val normalizedSize = outputFile.length()
            if (normalizedSize !in 1..MAX_NORMALIZED_BYTES) {
                return@withContext StickerImportPreparation.Rejected(StickerImportFailure.IMAGE_TOO_LARGE)
            }

            val metadata = StickerMetadataCodec.normalize(
                StickerMetadataCodec.defaultMetadata(resolveDisplayName(uri))
            ) ?: return@withContext StickerImportPreparation.Rejected(StickerImportFailure.NORMALIZATION_FAILED)
            val prepared = PreparedStickerImport(
                sourceUri = uri,
                stagingFile = outputFile,
                assetKey = outputFile.sha256(),
                mimeType = format.mimeType,
                extension = format.extension,
                byteSize = normalizedSize,
                width = normalizedBitmapWidth(outputFile, width),
                height = normalizedBitmapHeight(outputFile, height),
                metadata = metadata
            )
            normalizedFile = null
            StickerImportPreparation.Ready(prepared)
        } catch (_: Exception) {
            StickerImportPreparation.Rejected(StickerImportFailure.NORMALIZATION_FAILED)
        } finally {
            assetStore.deleteStagingFile(sourceFile)
            normalizedFile?.let(assetStore::deleteStagingFile)
        }
    }

    private fun copyIntoStaging(uri: Uri, destination: File): CopyResult {
        var copiedBytes = 0L
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (copiedBytes + count > MAX_INPUT_BYTES) return CopyResult.TooLarge
                        output.write(buffer, 0, count)
                        copiedBytes += count
                    }
                }
            } ?: CopyResult.Failed

            if (copiedBytes == 0L) CopyResult.Empty else CopyResult.Copied
        } catch (_: Exception) {
            CopyResult.Failed
        }
    }

    private fun resolveDisplayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_DRAFT_TITLE

    private fun orientBitmap(bitmap: Bitmap, sourceFile: File, format: StaticImageFormat): Bitmap {
        if (format != StaticImageFormat.JPEG) return bitmap
        val orientation = runCatching {
            ExifInterface(sourceFile.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = orientationMatrix(orientation) ?: return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun orientationMatrix(orientation: Int): Matrix? = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                preScale(-1f, 1f)
                postRotate(90f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                preScale(-1f, 1f)
                postRotate(-90f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            else -> return null
        }
    }

    private fun normalizedBitmapWidth(file: File, fallback: Int): Int = BitmapFactory.Options().let { options ->
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth.takeIf { it > 0 } ?: fallback
    }

    private fun normalizedBitmapHeight(file: File, fallback: Int): Int = BitmapFactory.Options().let { options ->
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outHeight.takeIf { it > 0 } ?: fallback
    }

    private fun detectFormat(bytes: ByteArray): StaticImageFormat? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            StaticImageFormat.JPEG

        bytes.startsWith(PNG_SIGNATURE) -> StaticImageFormat.PNG
        bytes.size >= 12 && bytes.startsWith(RIFF_SIGNATURE) && bytes.sliceMatches(8, WEBP_SIGNATURE) ->
            StaticImageFormat.WEBP

        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        prefix.indices.all { index -> this[index] == prefix[index] }

    private fun ByteArray.sliceMatches(start: Int, expected: ByteArray): Boolean = size >= start + expected.size &&
        expected.indices.all { index -> this[start + index] == expected[index] }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private enum class CopyResult {
        Copied,
        Empty,
        TooLarge,
        Failed
    }

    private enum class StaticImageFormat(
        val mimeType: String,
        val extension: String,
        val compressFormat: Bitmap.CompressFormat,
        val quality: Int
    ) {
        JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, 92),
        PNG("image/png", "png", Bitmap.CompressFormat.PNG, 100),
        WEBP("image/webp", "webp", Bitmap.CompressFormat.WEBP_LOSSLESS, 100);

        fun isAnimated(bytes: ByteArray): Boolean = when (this) {
            JPEG -> false
            PNG -> bytes.hasPngAnimationControlChunk()
            WEBP -> bytes.hasWebpAnimationChunk()
        }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 10L * 1024 * 1024
        const val MAX_NORMALIZED_BYTES = 8L * 1024 * 1024
        const val MAX_IMAGE_DIMENSION = 4096
        const val MAX_IMAGE_PIXELS = 4L * 1024 * 1024
        const val DEFAULT_DRAFT_TITLE = "新表情"
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        val WEBP_SIGNATURE = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    }
}

private fun ByteArray.hasPngAnimationControlChunk(): Boolean {
    var offset = 8
    while (offset + 12 <= size) {
        val length = ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
        if (length < 0 || offset + 12L + length > size) return false
        val type = String(this, offset + 4, 4, Charsets.US_ASCII)
        if (type == "acTL") return true
        if (type == "IEND") return false
        offset += 12 + length
    }
    return false
}

private fun ByteArray.hasWebpAnimationChunk(): Boolean {
    var offset = 12
    while (offset + 8 <= size) {
        val type = String(this, offset, 4, Charsets.US_ASCII)
        val length = (this[offset + 4].toInt() and 0xFF) or
            ((this[offset + 5].toInt() and 0xFF) shl 8) or
            ((this[offset + 6].toInt() and 0xFF) shl 16) or
            ((this[offset + 7].toInt() and 0xFF) shl 24)
        if (length < 0 || offset + 8L + length > size) return false
        if (type == "ANIM" || type == "ANMF") return true
        offset += 8 + length + (length and 1)
    }
    return false
}
