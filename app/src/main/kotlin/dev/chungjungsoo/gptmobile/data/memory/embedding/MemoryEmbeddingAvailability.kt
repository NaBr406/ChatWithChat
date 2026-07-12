package dev.chungjungsoo.gptmobile.data.memory.embedding

sealed interface MemoryEmbeddingAvailability {
    data object Available : MemoryEmbeddingAvailability

    data object Loading : MemoryEmbeddingAvailability

    data class Unavailable(
        val reason: Reason,
        val detail: String? = null
    ) : MemoryEmbeddingAvailability {
        init {
            require(detail == null || detail.isNotBlank()) { "detail must be null or non-blank" }
        }
    }

    enum class Reason {
        NOT_PROVISIONED,
        ARTIFACT_MISSING,
        ARTIFACT_CHECKSUM_MISMATCH,
        RUNTIME_INCOMPATIBLE,
        INITIALIZATION_FAILED
    }
}
