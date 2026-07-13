package dev.chungjungsoo.gptmobile.data.memory.embedding

import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexConfiguration
import java.util.concurrent.atomic.AtomicReference

fun interface MemoryEmbeddingCapabilitySource {
    fun current(): MemoryEmbeddingCapability
}

class MutableMemoryEmbeddingCapabilitySource(
    initialCapability: MemoryEmbeddingCapability = notProvisionedCapability()
) : MemoryEmbeddingCapabilitySource {
    private val capability = AtomicReference(initialCapability)

    override fun current(): MemoryEmbeddingCapability = capability.get()

    fun setReady(
        provider: MemoryEmbeddingProvider,
        configuration: MemoryVectorIndexConfiguration
    ): MemoryEmbeddingCapability.Ready {
        val ready = MemoryEmbeddingCapability.Ready(provider, configuration)
        capability.set(ready)
        return ready
    }

    fun setUnavailable(availability: MemoryEmbeddingAvailability): MemoryEmbeddingCapability.Unavailable {
        val unavailable = MemoryEmbeddingCapability.Unavailable(availability)
        capability.set(unavailable)
        return unavailable
    }

    fun compareAndSet(
        expected: MemoryEmbeddingCapability,
        updated: MemoryEmbeddingCapability
    ): Boolean = capability.compareAndSet(expected, updated)

    companion object {
        fun notProvisionedCapability(detail: String? = null): MemoryEmbeddingCapability.Unavailable =
            MemoryEmbeddingCapability.Unavailable(
                MemoryEmbeddingAvailability.Unavailable(
                    reason = MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED,
                    detail = detail
                )
            )
    }
}
