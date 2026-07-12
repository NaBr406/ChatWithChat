package dev.chungjungsoo.gptmobile.data.memory.embedding

import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexDefaults
import dev.chungjungsoo.gptmobile.di.MemoryRepositoryModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEmbeddingCapabilityTest {
    @Test
    fun `ready requires the provider descriptor to match the index configuration`() {
        val configuration = MemoryVectorIndexDefaults.configuration
        val provider = DeterministicFakeMemoryEmbeddingProvider(configuration.embeddingDescriptor)

        val capability = MemoryEmbeddingCapability.Ready(provider, configuration)

        assertSame(provider, capability.provider)
        assertSame(configuration, capability.configuration)
    }

    @Test
    fun `ready rejects a provider for a different embedding identity`() {
        val configuration = MemoryVectorIndexDefaults.configuration
        val provider = DeterministicFakeMemoryEmbeddingProvider(
            configuration.embeddingDescriptor.copy(modelVersion = "different-model-revision")
        )

        assertFailsWithIllegalArgument {
            MemoryEmbeddingCapability.Ready(provider, configuration)
        }
    }

    @Test
    fun `unavailable rejects an available provider state`() {
        assertFailsWithIllegalArgument {
            MemoryEmbeddingCapability.Unavailable(MemoryEmbeddingAvailability.Available)
        }
    }

    @Test
    fun `production dependency injection stays unavailable until provisioning gates pass`() {
        val capability = MemoryRepositoryModule.provideMemoryEmbeddingCapability()

        assertTrue(capability is MemoryEmbeddingCapability.Unavailable)
        val availability = (capability as MemoryEmbeddingCapability.Unavailable).availability
        assertTrue(availability is MemoryEmbeddingAvailability.Unavailable)
        assertEquals(
            MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED,
            (availability as MemoryEmbeddingAvailability.Unavailable).reason
        )
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
