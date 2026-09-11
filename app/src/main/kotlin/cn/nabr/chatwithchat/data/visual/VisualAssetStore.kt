package cn.nabr.chatwithchat.data.visual

import android.content.Context
import android.net.Uri
import cn.nabr.chatwithchat.util.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisualAssetStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun rootDirectory(): File = File(context.filesDir, ROOT_DIRECTORY_NAME).apply {
        if (!exists()) mkdirs()
    }

    fun sha256(sourcePath: String): String? = runCatching {
        val input = openInputStream(sourcePath) ?: return@runCatching null
        input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }.getOrNull()

    fun ensureOriginalStored(
        assetHash: String,
        mimeType: String,
        sourcePath: String
    ): String? {
        val source = openInputStream(sourcePath) ?: return null
        val extension = extensionForMimeType(mimeType)
        val destination = File(rootDirectory(), "$assetHash.$extension")
        if (destination.isFile && destination.length() > 0L) {
            source.close()
            return destination.absolutePath
        }

        val temporary = File(rootDirectory(), ".$assetHash.tmp")
        return try {
            source.use { input ->
                FileOutputStream(temporary, false).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.fd.sync()
                }
            }
            if (temporary.length() <= 0L) return null
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            destination.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        } catch (_: Throwable) {
            temporary.delete()
            null
        }
    }

    fun readBytes(path: String): ByteArray? = runCatching {
        File(path).takeIf(File::isFile)?.readBytes()
    }.getOrNull()

    fun verifyHash(path: String, expectedHash: String): Boolean =
        sha256(path)?.equals(expectedHash, ignoreCase = true) == true

    fun deleteIfExists(path: String?): Boolean {
        val file = path?.let(::File) ?: return false
        return !file.exists() || file.delete()
    }

    fun listFiles(): List<File> = rootDirectory()
        .listFiles()
        ?.filter { file -> file.isFile && !file.name.startsWith(".") }
        .orEmpty()

    fun compactionPath(assetHash: String, extension: String = "webp"): String =
        File(rootDirectory(), "${assetHash}_compacted_v1.$extension").absolutePath

    fun temporaryCompactionPath(assetHash: String, extension: String = "webp"): String =
        File(rootDirectory(), ".${assetHash}_compacted_v1.tmp.$extension").absolutePath

    fun replaceAtomically(temporaryPath: String, destinationPath: String): Boolean {
        val temporary = File(temporaryPath)
        val destination = File(destinationPath)
        if (!temporary.isFile) return false
        if (destination.exists() && !destination.delete()) return false
        if (temporary.renameTo(destination)) return true
        return runCatching {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }.getOrDefault(false)
    }

    private fun openInputStream(path: String): InputStream? = runCatching {
        when {
            path.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(path))
            path.startsWith("file://") -> FileInputStream(File(path.removePrefix("file://")))
            else -> FileInputStream(File(path))
        }
    }.getOrNull()

    private fun extensionForMimeType(mimeType: String): String = when {
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        mimeType.contains("png") -> "png"
        mimeType.contains("webp") -> "webp"
        mimeType.contains("gif") -> "gif"
        mimeType.contains("bmp") -> "bmp"
        mimeType.contains("tiff") -> "tiff"
        else -> FileUtils.getMimeTypeFromPath("asset.$mimeType").substringAfter('/').ifBlank { "bin" }
    }

    private companion object {
        const val ROOT_DIRECTORY_NAME = "visual_assets"
        const val HASH_BUFFER_BYTES = 64 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
