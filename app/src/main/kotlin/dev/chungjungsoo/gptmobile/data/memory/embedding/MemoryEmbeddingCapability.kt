package dev.chungjungsoo.gptmobile.data.memory.embedding

import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexConfiguration

sealed interface MemoryEmbeddingCapability {
    data class Ready(
        val provider: MemoryEmbeddingProvider,
        val configuration: MemoryVectorIndexConfiguration
    ) : MemoryEmbeddingCapability {
        init {
            require(provider.descriptor == configuration.embeddingDescriptor) {
                "Embedding provider descriptor must match the vector index configuration"
            }
        }
    }

    data class Unavailable(
        val availability: MemoryEmbeddingAvailability
    ) : MemoryEmbeddingCapability {
        init {
            require(availability != MemoryEmbeddingAvailability.Available) {
                "Unavailable embedding capability cannot contain an available provider"
            }
        }
    }
}
