package dev.chungjungsoo.gptmobile.data.memory.embedding

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryEmbeddingArtifactInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `verified bundled artifacts install under the checksum version atomically`() {
        val fixture = fixture()

        val result = fixture.installer.install()

        assertTrue(result is MemoryEmbeddingArtifactInstallResult.Success)
        val installed = (result as MemoryEmbeddingArtifactInstallResult.Success).artifacts
        assertEquals(fixture.version, installed.rootDirectory.name)
        assertArrayEquals(fixture.assets.getValue("asset/model.onnx"), installed.modelFile.readBytes())
        assertArrayEquals(fixture.assets.getValue("asset/vocab.txt"), installed.vocabFile.readBytes())
        assertFalse(File(installed.rootDirectory.parentFile, "${fixture.version}.installing").exists())
        assertFalse(File(installed.rootDirectory.parentFile, "${fixture.version}.previous").exists())
    }

    @Test
    fun `missing bundled artifact remains not provisioned even when an installed copy exists`() {
        val fixture = fixture()
        assertTrue(fixture.installer.install() is MemoryEmbeddingArtifactInstallResult.Success)
        fixture.assets.remove("asset/tokenizer.json")

        val result = fixture.installer.install()

        assertUnavailable(result, MemoryEmbeddingAvailability.Reason.ARTIFACT_MISSING)
        val installedModel = File(fixture.installDirectory(), "model.onnx")
        assertArrayEquals(MODEL_BYTES, installedModel.readBytes())
    }

    @Test
    fun `bundled checksum mismatch never creates a ready installation`() {
        val fixture = fixture()
        fixture.assets["asset/model.onnx"] = "tampered".toByteArray()

        val result = fixture.installer.install()

        assertUnavailable(result, MemoryEmbeddingAvailability.Reason.ARTIFACT_CHECKSUM_MISMATCH)
        assertFalse(fixture.installDirectory().exists())
    }

    @Test
    fun `verified bundle repairs a corrupted installed artifact`() {
        val fixture = fixture()
        val first = fixture.installer.install() as MemoryEmbeddingArtifactInstallResult.Success
        first.artifacts.modelFile.writeText("corrupt")

        val second = fixture.installer.install()

        assertTrue(second is MemoryEmbeddingArtifactInstallResult.Success)
        val installed = (second as MemoryEmbeddingArtifactInstallResult.Success).artifacts
        assertArrayEquals(MODEL_BYTES, installed.modelFile.readBytes())
    }

    private fun fixture(): InstallerFixture {
        val root = temporaryFolder.newFolder()
        val assets = linkedMapOf(
            "asset/model.onnx" to MODEL_BYTES,
            "asset/tokenizer.json" to TOKENIZER_BYTES,
            "asset/tokenizer_config.json" to TOKENIZER_CONFIG_BYTES,
            "asset/vocab.txt" to VOCAB_BYTES
        )
        val artifacts = assets.map { (path, bytes) ->
            MemoryEmbeddingArtifact(
                relativePath = path.removePrefix("asset/"),
                sizeBytes = bytes.size.toLong(),
                sha256 = bytes.sha256()
            )
        }
        val version = artifacts.first().sha256
        val installer = MemoryEmbeddingArtifactInstaller(
            artifactSource = MemoryEmbeddingArtifactSource { path ->
                ByteArrayInputStream(assets[path] ?: throw FileNotFoundException(path))
            },
            noBackupFilesDir = root,
            contract = MemoryEmbeddingArtifactInstaller.ArtifactContract(
                assetRoot = "asset",
                installRoot = "models/test",
                installVersion = version,
                artifacts = artifacts
            )
        )
        return InstallerFixture(root, assets, version, installer)
    }

    private fun assertUnavailable(
        result: MemoryEmbeddingArtifactInstallResult,
        reason: MemoryEmbeddingAvailability.Reason
    ) {
        assertTrue(result is MemoryEmbeddingArtifactInstallResult.NotProvisioned)
        assertEquals(
            reason,
            (result as MemoryEmbeddingArtifactInstallResult.NotProvisioned).availability.reason
        )
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class InstallerFixture(
        val root: File,
        val assets: MutableMap<String, ByteArray>,
        val version: String,
        val installer: MemoryEmbeddingArtifactInstaller
    ) {
        fun installDirectory(): File = File(root, "models/test/$version")
    }

    private companion object {
        val MODEL_BYTES = "model".toByteArray()
        val TOKENIZER_BYTES = "tokenizer".toByteArray()
        val TOKENIZER_CONFIG_BYTES = "tokenizer-config".toByteArray()
        val VOCAB_BYTES = "[PAD]\n[UNK]\n[CLS]\n[SEP]".toByteArray()
    }
}
