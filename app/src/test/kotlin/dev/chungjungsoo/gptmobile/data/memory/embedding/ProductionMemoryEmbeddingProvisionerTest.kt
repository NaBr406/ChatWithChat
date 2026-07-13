package dev.chungjungsoo.gptmobile.data.memory.embedding

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductionMemoryEmbeddingProvisionerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `verified artifacts and initialized provider transition capability to ready`() = runBlocking {
        val fixture = fixture()
        val provider = DeterministicFakeMemoryEmbeddingProvider(
            ProductionMemoryEmbeddingArtifactContract.descriptor
        )
        val source = MutableMemoryEmbeddingCapabilitySource()
        val provisioner = ProductionMemoryEmbeddingProvisioner(
            artifactInstaller = fixture.installer,
            capabilitySource = source,
            providerFactory = ProductionMemoryEmbeddingProviderFactory { Result.success(provider) },
            provisioningDispatcher = Dispatchers.Unconfined
        )

        val result = provisioner.provision()

        assertTrue(result is MemoryEmbeddingCapability.Ready)
        assertSame(result, source.current())
        assertSame(provider, (result as MemoryEmbeddingCapability.Ready).provider)
    }

    @Test
    fun `missing artifact remains not provisioned and never creates a provider`() = runBlocking {
        val fixture = fixture()
        fixture.assets.remove("asset/model.onnx")
        val source = MutableMemoryEmbeddingCapabilitySource()
        var providerFactoryCalls = 0
        val provisioner = ProductionMemoryEmbeddingProvisioner(
            artifactInstaller = fixture.installer,
            capabilitySource = source,
            providerFactory = ProductionMemoryEmbeddingProviderFactory {
                providerFactoryCalls += 1
                error("Provider factory must not run")
            },
            provisioningDispatcher = Dispatchers.Unconfined
        )

        val result = provisioner.provision()

        assertEquals(0, providerFactoryCalls)
        assertTrue(result is MemoryEmbeddingCapability.Unavailable)
        val availability = (result as MemoryEmbeddingCapability.Unavailable).availability
            as MemoryEmbeddingAvailability.Unavailable
        assertEquals(MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED, availability.reason)
        assertTrue(availability.detail.orEmpty().startsWith("ARTIFACT_MISSING:"))
    }

    @Test
    fun `provider initialization failure never publishes ready`() = runBlocking {
        val fixture = fixture()
        val source = MutableMemoryEmbeddingCapabilitySource()
        val provisioner = ProductionMemoryEmbeddingProvisioner(
            artifactInstaller = fixture.installer,
            capabilitySource = source,
            providerFactory = ProductionMemoryEmbeddingProviderFactory {
                Result.failure(IllegalStateException("ORT session failed"))
            },
            provisioningDispatcher = Dispatchers.Unconfined
        )

        val result = provisioner.provision()

        assertTrue(result is MemoryEmbeddingCapability.Unavailable)
        val availability = (result as MemoryEmbeddingCapability.Unavailable).availability
            as MemoryEmbeddingAvailability.Unavailable
        assertEquals(MemoryEmbeddingAvailability.Reason.INITIALIZATION_FAILED, availability.reason)
        assertEquals("ORT session failed", availability.detail)
    }

    @Test
    fun `provider self test must produce a finite normalized 512 dimensional embedding`() = runBlocking {
        val invalidEmbeddings = listOf(
            floatArrayOf(1f),
            FloatArray(ProductionMemoryEmbeddingArtifactContract.EMBEDDING_DIMENSION).also { it[0] = Float.NaN },
            FloatArray(ProductionMemoryEmbeddingArtifactContract.EMBEDDING_DIMENSION) { 1f }
        )

        invalidEmbeddings.forEach { invalidEmbedding ->
            val fixture = fixture()
            val source = MutableMemoryEmbeddingCapabilitySource()
            val malformedProvider = object : MemoryEmbeddingProvider {
                override val descriptor = ProductionMemoryEmbeddingArtifactContract.descriptor

                override suspend fun availability() = MemoryEmbeddingAvailability.Available

                override suspend fun embedDocuments(texts: List<String>) =
                    Result.success(texts.map { FloatArray(descriptor.dimension) })

                override suspend fun embedQuery(text: String) = Result.success(invalidEmbedding)
            }
            val provisioner = ProductionMemoryEmbeddingProvisioner(
                artifactInstaller = fixture.installer,
                capabilitySource = source,
                providerFactory = ProductionMemoryEmbeddingProviderFactory { Result.success(malformedProvider) },
                provisioningDispatcher = Dispatchers.Unconfined
            )

            val result = provisioner.provision()

            assertTrue(result is MemoryEmbeddingCapability.Unavailable)
            val availability = (result as MemoryEmbeddingCapability.Unavailable).availability
                as MemoryEmbeddingAvailability.Unavailable
            assertEquals(MemoryEmbeddingAvailability.Reason.INITIALIZATION_FAILED, availability.reason)
        }
    }

    private fun fixture(): InstallerFixture {
        val root = temporaryFolder.newFolder()
        val assets = linkedMapOf(
            "asset/model.onnx" to "model".toByteArray(),
            "asset/tokenizer.json" to "tokenizer".toByteArray(),
            "asset/tokenizer_config.json" to "tokenizer-config".toByteArray(),
            "asset/vocab.txt" to "vocab".toByteArray()
        )
        val artifacts = assets.map { (path, bytes) ->
            MemoryEmbeddingArtifact(
                relativePath = path.removePrefix("asset/"),
                sizeBytes = bytes.size.toLong(),
                sha256 = bytes.sha256()
            )
        }
        val installer = MemoryEmbeddingArtifactInstaller(
            artifactSource = MemoryEmbeddingArtifactSource { path ->
                ByteArrayInputStream(assets[path] ?: throw FileNotFoundException(path))
            },
            noBackupFilesDir = root,
            contract = MemoryEmbeddingArtifactInstaller.ArtifactContract(
                assetRoot = "asset",
                installRoot = "models/test",
                installVersion = "version",
                artifacts = artifacts
            )
        )
        return InstallerFixture(assets, installer)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class InstallerFixture(
        val assets: MutableMap<String, ByteArray>,
        val installer: MemoryEmbeddingArtifactInstaller
    )
}
