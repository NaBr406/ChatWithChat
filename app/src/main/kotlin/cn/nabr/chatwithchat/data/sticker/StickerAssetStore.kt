package cn.nabr.chatwithchat.data.sticker

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import cn.nabr.chatwithchat.data.database.entity.StickerAssetEntity
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class StoredStickerAsset(
    val relativePath: String,
    val wasCreated: Boolean
)

internal const val STICKER_STAGING_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L

@Singleton
class StickerAssetStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val rootDirectory: File
        get() = File(context.filesDir, ROOT_DIRECTORY_NAME)

    private val localAssetsDirectory: File
        get() = File(rootDirectory, LOCAL_ASSETS_DIRECTORY_NAME)

    private val stagingDirectory: File
        get() = File(context.cacheDir, "$ROOT_DIRECTORY_NAME/$STAGING_DIRECTORY_NAME")

    fun createStagingFile(): File? {
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) return null
        cleanupStagingFiles()
        return runCatching { File.createTempFile("sticker_", ".tmp", stagingDirectory) }.getOrNull()
    }

    internal fun cleanupStagingFiles(nowMillis: Long = System.currentTimeMillis()) {
        stagingDirectory.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    nowMillis >= file.lastModified() &&
                    nowMillis - file.lastModified() > STICKER_STAGING_RETENTION_MILLIS
            }
            .forEach { file -> file.delete() }
    }

    fun promote(stagingFile: File, assetKey: String, extension: String): StoredStickerAsset? {
        if (!isAssetKey(assetKey) || !extension.matches(EXTENSION_REGEX)) {
            stagingFile.delete()
            return null
        }
        if (!stagingFile.isFile) return null
        if (!localAssetsDirectory.exists() && !localAssetsDirectory.mkdirs()) {
            stagingFile.delete()
            return null
        }

        val relativePath = "$LOCAL_ASSETS_DIRECTORY_NAME/$assetKey.$extension"
        val targetFile = managedLocalFile(relativePath) ?: return null
        if (targetFile.exists()) {
            if (targetFile.sha256OrNull() == assetKey) {
                stagingFile.delete()
                return StoredStickerAsset(relativePath, wasCreated = false)
            }
            if (!targetFile.delete()) {
                stagingFile.delete()
                return null
            }
        }

        return try {
            try {
                Files.move(stagingFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                stagingFile.delete()
                return null
            }
            StoredStickerAsset(relativePath, wasCreated = true)
        } catch (_: FileAlreadyExistsException) {
            stagingFile.delete()
            if (targetFile.sha256OrNull() == assetKey) {
                StoredStickerAsset(relativePath, wasCreated = false)
            } else {
                null
            }
        } catch (_: Exception) {
            stagingFile.delete()
            null
        }
    }

    fun open(asset: StickerAssetEntity): InputStream? = try {
        when (asset.storageKind) {
            STICKER_STORAGE_KIND_BUNDLED -> {
                asset.relativePath.takeIf(::isSafeBundledAssetPath)?.let(context.assets::open)
            }

            STICKER_STORAGE_KIND_LOCAL -> managedLocalFile(asset.relativePath)
                ?.takeIf(File::isFile)
                ?.inputStream()

            else -> null
        }
    } catch (_: Exception) {
        null
    }

    fun verify(asset: StickerAssetEntity): Boolean {
        if (!isAssetKey(asset.assetKey) || asset.byteSize <= 0L) return false
        return open(asset)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                byteCount += count
                digest.update(buffer, 0, count)
            }
            byteCount == asset.byteSize && digest.digest().toHex() == asset.assetKey
        } ?: false
    }

    fun deleteLocal(asset: StickerAssetEntity): Boolean {
        if (asset.storageKind != STICKER_STORAGE_KIND_LOCAL) return false
        val file = managedLocalFile(asset.relativePath) ?: return false
        return !file.exists() || file.delete()
    }

    fun deleteStagingFile(file: File) {
        file.delete()
    }

    private fun managedLocalFile(relativePath: String): File? {
        if (relativePath.isBlank() || File(relativePath).isAbsolute || '\\' in relativePath) return null
        val file = File(rootDirectory, relativePath).canonicalFile
        val root = rootDirectory.canonicalFile.toPath()
        return file.takeIf { candidate -> candidate.toPath().startsWith(root) }
    }

    private fun isSafeBundledAssetPath(path: String): Boolean = path.startsWith("stickers/") &&
        !path.contains("\\") &&
        path.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." }

    private fun isAssetKey(assetKey: String): Boolean = ASSET_KEY_REGEX.matches(assetKey)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun File.sha256OrNull(): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private companion object {
        const val ROOT_DIRECTORY_NAME = "stickers"
        const val LOCAL_ASSETS_DIRECTORY_NAME = "assets"
        const val STAGING_DIRECTORY_NAME = ".staging"
        val ASSET_KEY_REGEX = Regex("[a-f0-9]{64}")
        val EXTENSION_REGEX = Regex("[a-z0-9]{1,8}")
    }
}
