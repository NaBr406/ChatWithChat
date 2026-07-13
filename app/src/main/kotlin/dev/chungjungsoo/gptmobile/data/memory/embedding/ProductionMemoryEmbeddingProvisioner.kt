package dev.chungjungsoo.gptmobile.data.memory.embedding

import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexConfiguration
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexDefaults
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface ProductionMemoryEmbeddingProviderFactory {
    fun create(artifacts: MemoryEmbeddingInstalledArtifacts): Result<MemoryEmbeddingProvider>
}

class ProductionMemoryEmbeddingProvisioner(
    private val artifactInstaller: MemoryEmbeddingArtifactInstaller,
    private val capabilitySource: MutableMemoryEmbeddingCapabilitySource,
    private val configuration: MemoryVectorIndexConfiguration = MemoryVectorIndexDefaults.configuration,
    private val providerFactory: ProductionMemoryEmbeddingProviderFactory =
        ProductionMemoryEmbeddingProviderFactory { artifacts -> OnnxMemoryEmbeddingProvider.create(artifacts) },
    private val provisioningDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {
    private val provisionMutex = Mutex()
    private var ownedProvider: MemoryEmbeddingProvider? = null
    private var isClosed = false

    init {
        require(configuration.embeddingDescriptor == ProductionMemoryEmbeddingArtifactContract.descriptor) {
            "The production provisioner requires the pinned embedding descriptor"
        }
    }

    suspend fun provision(): MemoryEmbeddingCapability = provisionMutex.withLock {
        try {
            provisionWhileLocked()
        } catch (error: CancellationException) {
            capabilitySource.setUnavailable(
                MemoryEmbeddingAvailability.Unavailable(
                    MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED,
                    "Embedding provisioning was cancelled"
                )
            )
            throw error
        }
    }

    private suspend fun provisionWhileLocked(): MemoryEmbeddingCapability {
        closeOwnedProvider()
        if (isClosed) {
            return capabilitySource.setUnavailable(
                MemoryEmbeddingAvailability.Unavailable(
                    MemoryEmbeddingAvailability.Reason.INITIALIZATION_FAILED,
                    "The embedding provisioner is closed"
                )
            )
        }

        capabilitySource.setUnavailable(MemoryEmbeddingAvailability.Loading)
        return when (val installResult = withContext(provisioningDispatcher) { artifactInstaller.install() }) {
            is MemoryEmbeddingArtifactInstallResult.NotProvisioned -> {
                val issue = installResult.availability
                capabilitySource.setUnavailable(
                    MemoryEmbeddingAvailability.Unavailable(
                        reason = MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED,
                        detail = buildString {
                            append(issue.reason.name)
                            issue.detail?.let { detail -> append(": ").append(detail) }
                        }
                    )
                )
            }
            is MemoryEmbeddingArtifactInstallResult.Success -> initializeProvider(installResult.artifacts)
        }
    }

    override fun close() {
        kotlinx.coroutines.runBlocking {
            provisionMutex.withLock {
                isClosed = true
                closeOwnedProvider()
                capabilitySource.setUnavailable(
                    MemoryEmbeddingAvailability.Unavailable(
                        MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED,
                        "The embedding runtime is closed"
                    )
                )
            }
        }
    }

    private suspend fun initializeProvider(
        artifacts: MemoryEmbeddingInstalledArtifacts
    ): MemoryEmbeddingCapability {
        var candidate: MemoryEmbeddingProvider? = null
        return try {
            val result = withContext(provisioningDispatcher) { providerFactory.create(artifacts) }
            val provider = result.getOrThrow()
            candidate = provider
            check(provider.descriptor == configuration.embeddingDescriptor) {
                "The provisioned provider does not match the production embedding descriptor"
            }
            check(provider.availability() == MemoryEmbeddingAvailability.Available) {
                "The provisioned provider did not initialize as available"
            }
            val selfTestEmbedding = provider.embedQuery(SELF_TEST_QUERY).getOrThrow()
            check(selfTestEmbedding.size == ProductionMemoryEmbeddingArtifactContract.EMBEDDING_DIMENSION) {
                "The embedding self-test returned an unexpected dimension"
            }
            check(selfTestEmbedding.all(Float::isFinite)) {
                "The embedding self-test returned a non-finite value"
            }
            val squaredNorm = selfTestEmbedding.sumOf { value -> (value * value).toDouble() }
            check(abs(squaredNorm - 1.0) <= SELF_TEST_NORM_TOLERANCE) {
                "The embedding self-test did not return an L2-normalized vector"
            }
            val ready = capabilitySource.setReady(provider, configuration)
            ownedProvider = provider
            ready
        } catch (error: CancellationException) {
            closeProvider(candidate)
            throw error
        } catch (error: Exception) {
            closeProvider(candidate)
            capabilitySource.setUnavailable(
                MemoryEmbeddingAvailability.Unavailable(
                    MemoryEmbeddingAvailability.Reason.INITIALIZATION_FAILED,
                    error.message ?: error.javaClass.simpleName
                )
            )
        }
    }

    private fun closeOwnedProvider() {
        closeProvider(ownedProvider)
        ownedProvider = null
    }

    private fun closeProvider(provider: MemoryEmbeddingProvider?) {
        runCatching { (provider as? AutoCloseable)?.close() }
    }

    private companion object {
        const val SELF_TEST_QUERY = "\u8bb0\u5fc6\u68c0\u7d22\u521d\u59cb\u5316"
        const val SELF_TEST_NORM_TOLERANCE = 0.0001
    }
}
