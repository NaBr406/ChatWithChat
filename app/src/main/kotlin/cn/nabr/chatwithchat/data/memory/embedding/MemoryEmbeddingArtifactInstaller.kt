package cn.nabr.chatwithchat.data.memory.embedding

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

fun interface MemoryEmbeddingArtifactSource {
    @Throws(IOException::class)
    fun open(relativePath: String): InputStream
}

class MemoryEmbeddingArtifactInstaller(
    private val artifactSource: MemoryEmbeddingArtifactSource,
    private val noBackupFilesDir: File,
    private val contract: ArtifactContract = ArtifactContract.production()
) {
    fun install(): MemoryEmbeddingArtifactInstallResult {
        val installParent = File(noBackupFilesDir, contract.installRoot)
        if (!installParent.exists() && !installParent.mkdirs()) {
            return notProvisioned(
                MemoryEmbeddingAvailability.Reason.INITIALIZATION_FAILED,
                "Unable to create the embedding artifact directory"
            )
        }

        return synchronized(PROCESS_INSTALL_LOCK) {
            runCatching {
                RandomAccessFile(File(installParent, LOCK_FILE), "rw").use { lockFile ->
                    lockFile.channel.use { channel ->
                        channel.lock().use {
                            installWhileLocked(installParent)
                        }
                    }
                }
            }.getOrElse { error ->
                when (error) {
                    is ArtifactMissingException -> notProvisioned(
                        MemoryEmbeddingAvailability.Reason.ARTIFACT_MISSING,
                        error.message
                    )
                    is ArtifactChecksumException -> notProvisioned(
                        MemoryEmbeddingAvailability.Reason.ARTIFACT_CHECKSUM_MISMATCH,
                        error.message
                    )
                    else -> notProvisioned(
                        MemoryEmbeddingAvailability.Reason.INITIALIZATION_FAILED,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun installWhileLocked(installParent: File): MemoryEmbeddingArtifactInstallResult.Success {
        val destination = File(installParent, contract.installVersion)
        val staging = File(installParent, "${contract.installVersion}$STAGING_SUFFIX")
        val previous = File(installParent, "${contract.installVersion}$PREVIOUS_SUFFIX")

        recoverInterruptedSwap(destination, previous)
        staging.deleteIfPresent()
        verifyBundledArtifacts()

        if (destination.isDirectory && verifyDirectory(destination) == null) {
            previous.deleteIfPresent()
            return success(destination)
        }

        copyAndVerifyBundledArtifacts(staging)
        previous.deleteIfPresent()
        if (destination.exists() && !destination.renameTo(previous)) {
            staging.deleteIfPresent()
            throw IOException("Unable to preserve the previous embedding artifact installation")
        }

        if (!staging.renameTo(destination)) {
            if (previous.exists()) previous.renameTo(destination)
            staging.deleteIfPresent()
            throw IOException("Unable to atomically install embedding artifacts")
        }

        val installedError = verifyDirectory(destination)
        if (installedError != null) {
            destination.deleteIfPresent()
            if (previous.exists()) previous.renameTo(destination)
            throw installedError
        }

        previous.deleteIfPresent()
        return success(destination)
    }

    private fun recoverInterruptedSwap(destination: File, previous: File) {
        if (!destination.exists() && previous.exists() && !previous.renameTo(destination)) {
            throw IOException("Unable to recover the previous embedding artifact installation")
        }
    }

    private fun verifyBundledArtifacts() {
        contract.artifacts.forEach { artifact ->
            val assetPath = "${contract.assetRoot}/${artifact.relativePath}"
            val input = openBundledArtifact(assetPath, artifact.relativePath)
            input.use { source ->
                verifyInput(source, artifact, "Bundled")?.let { throw it }
            }
        }
    }

    private fun copyAndVerifyBundledArtifacts(staging: File) {
        if (!staging.mkdirs()) throw IOException("Unable to create the embedding artifact staging directory")
        try {
            contract.artifacts.forEach { artifact ->
                val target = artifactFile(staging, artifact.relativePath)
                target.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IOException("Unable to create an embedding artifact staging directory")
                    }
                }
                val assetPath = "${contract.assetRoot}/${artifact.relativePath}"
                val input = openBundledArtifact(assetPath, artifact.relativePath)
                input.use { source ->
                    target.outputStream().buffered().use { output ->
                        source.copyTo(output)
                    }
                }
                verifyFile(target, artifact, "Bundled")?.let { throw it }
            }
        } catch (error: Throwable) {
            staging.deleteIfPresent()
            throw error
        }
    }

    private fun openBundledArtifact(assetPath: String, relativePath: String): InputStream = try {
        artifactSource.open(assetPath)
    } catch (error: FileNotFoundException) {
        throw ArtifactMissingException("Bundled embedding artifact is missing: $relativePath", error)
    } catch (error: IOException) {
        throw ArtifactMissingException("Bundled embedding artifact cannot be opened: $relativePath", error)
    }

    private fun verifyInput(
        input: InputStream,
        artifact: MemoryEmbeddingArtifact,
        location: String
    ): ArtifactVerificationException? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            digest.update(buffer, 0, count)
        }
        if (size != artifact.sizeBytes) {
            return ArtifactChecksumException("$location embedding artifact size mismatch: ${artifact.relativePath}")
        }
        val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (sha256 != artifact.sha256) {
            return ArtifactChecksumException("$location embedding artifact checksum mismatch: ${artifact.relativePath}")
        }
        return null
    }

    private fun verifyDirectory(directory: File): ArtifactVerificationException? {
        contract.artifacts.forEach { artifact ->
            val file = artifactFile(directory, artifact.relativePath)
            if (!file.isFile) {
                return ArtifactMissingException("Installed embedding artifact is missing: ${artifact.relativePath}")
            }
            verifyFile(file, artifact, "Installed")?.let { return it }
        }
        return null
    }

    private fun verifyFile(
        file: File,
        artifact: MemoryEmbeddingArtifact,
        location: String
    ): ArtifactVerificationException? {
        if (file.length() != artifact.sizeBytes) {
            return ArtifactChecksumException("$location embedding artifact size mismatch: ${artifact.relativePath}")
        }
        if (file.sha256() != artifact.sha256) {
            return ArtifactChecksumException("$location embedding artifact checksum mismatch: ${artifact.relativePath}")
        }
        return null
    }

    private fun success(destination: File): MemoryEmbeddingArtifactInstallResult.Success =
        MemoryEmbeddingArtifactInstallResult.Success(
            MemoryEmbeddingInstalledArtifacts(
                rootDirectory = destination,
                modelFile = artifactFile(destination, ProductionMemoryEmbeddingArtifactContract.MODEL_FILE),
                tokenizerJsonFile = artifactFile(
                    destination,
                    ProductionMemoryEmbeddingArtifactContract.TOKENIZER_JSON_FILE
                ),
                tokenizerConfigFile = artifactFile(
                    destination,
                    ProductionMemoryEmbeddingArtifactContract.TOKENIZER_CONFIG_FILE
                ),
                vocabFile = artifactFile(destination, ProductionMemoryEmbeddingArtifactContract.VOCAB_FILE)
            )
        )

    private fun artifactFile(root: File, relativePath: String): File {
        val file = File(root, relativePath)
        val rootPath = root.canonicalFile.toPath()
        require(file.canonicalFile.toPath().startsWith(rootPath)) { "Artifact path escapes its installation root" }
        return file
    }

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
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun File.deleteIfPresent() {
        if (exists() && !deleteRecursively()) throw IOException("Unable to clean embedding artifact staging state")
    }

    companion object {
        private const val LOCK_FILE = ".provision.lock"
        private const val STAGING_SUFFIX = ".installing"
        private const val PREVIOUS_SUFFIX = ".previous"
        private val PROCESS_INSTALL_LOCK = Any()

        fun fromContext(context: Context): MemoryEmbeddingArtifactInstaller =
            MemoryEmbeddingArtifactInstaller(
                artifactSource = MemoryEmbeddingArtifactSource { path -> context.assets.open(path) },
                noBackupFilesDir = context.noBackupFilesDir
            )
    }

    data class ArtifactContract(
        val assetRoot: String,
        val installRoot: String,
        val installVersion: String,
        val artifacts: List<MemoryEmbeddingArtifact>
    ) {
        init {
            require(assetRoot.isNotBlank()) { "assetRoot must not be blank" }
            require(installRoot.isNotBlank()) { "installRoot must not be blank" }
            require(installVersion.isNotBlank()) { "installVersion must not be blank" }
            require(artifacts.isNotEmpty()) { "artifacts must not be empty" }
            require(artifacts.map { artifact -> artifact.relativePath }.distinct().size == artifacts.size) {
                "artifact paths must be unique"
            }
        }

        companion object {
            fun production() = ArtifactContract(
                assetRoot = ProductionMemoryEmbeddingArtifactContract.ASSET_ROOT,
                installRoot = ProductionMemoryEmbeddingArtifactContract.INSTALL_ROOT,
                installVersion = ProductionMemoryEmbeddingArtifactContract.MODEL_SHA256,
                artifacts = ProductionMemoryEmbeddingArtifactContract.artifacts
            )
        }
    }
}

sealed interface MemoryEmbeddingArtifactInstallResult {
    data class Success(val artifacts: MemoryEmbeddingInstalledArtifacts) : MemoryEmbeddingArtifactInstallResult

    data class NotProvisioned(
        val availability: MemoryEmbeddingAvailability.Unavailable
    ) : MemoryEmbeddingArtifactInstallResult
}

data class MemoryEmbeddingInstalledArtifacts(
    val rootDirectory: File,
    val modelFile: File,
    val tokenizerJsonFile: File,
    val tokenizerConfigFile: File,
    val vocabFile: File
)

private fun notProvisioned(
    reason: MemoryEmbeddingAvailability.Reason,
    detail: String?
): MemoryEmbeddingArtifactInstallResult.NotProvisioned =
    MemoryEmbeddingArtifactInstallResult.NotProvisioned(
        MemoryEmbeddingAvailability.Unavailable(reason = reason, detail = detail?.takeIf(String::isNotBlank))
    )

private sealed class ArtifactVerificationException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

private class ArtifactMissingException(message: String, cause: Throwable? = null) :
    ArtifactVerificationException(message, cause)

private class ArtifactChecksumException(message: String, cause: Throwable? = null) :
    ArtifactVerificationException(message, cause)
