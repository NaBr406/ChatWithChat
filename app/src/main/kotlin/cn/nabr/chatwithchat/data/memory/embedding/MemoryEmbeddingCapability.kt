package cn.nabr.chatwithchat.data.memory.embedding

import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexConfiguration

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
