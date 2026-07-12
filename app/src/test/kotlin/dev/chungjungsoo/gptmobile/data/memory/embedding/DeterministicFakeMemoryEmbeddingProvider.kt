package dev.chungjungsoo.gptmobile.data.memory.embedding

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.sqrt

internal class DeterministicFakeMemoryEmbeddingProvider(
    override val descriptor: MemoryEmbeddingDescriptor = TEST_DESCRIPTOR,
    var availabilityState: MemoryEmbeddingAvailability = MemoryEmbeddingAvailability.Available
) : MemoryEmbeddingProvider {
    init {
        require(descriptor.normalized) { "The deterministic fake only emits normalized embeddings" }
    }

    override suspend fun availability(): MemoryEmbeddingAvailability = availabilityState

    override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> =
        when (val availability = availabilityState) {
            MemoryEmbeddingAvailability.Available -> Result.success(
                texts.map { text -> embed(descriptor.documentPrefix + text) }
            )
            else -> Result.failure(unavailableException(availability))
        }

    override suspend fun embedQuery(text: String): Result<FloatArray> =
        when (val availability = availabilityState) {
            MemoryEmbeddingAvailability.Available -> Result.success(embed(descriptor.queryPrefix + text))
            else -> Result.failure(unavailableException(availability))
        }

    private fun embed(text: String): FloatArray {
        val vector = FloatArray(descriptor.dimension)
        var vectorIndex = 0
        var blockIndex = 0
        while (vectorIndex < vector.size) {
            val digest = MessageDigest.getInstance("SHA-256").digest(
                "$blockIndex\u0000$text".toByteArray(StandardCharsets.UTF_8)
            )
            digest.forEach { byte ->
                if (vectorIndex < vector.size) {
                    vector[vectorIndex++] = ((byte.toInt() and 0xff) - BYTE_MIDPOINT) / BYTE_MIDPOINT
                }
            }
            blockIndex += 1
        }
        val norm = sqrt(vector.sumOf { value -> (value * value).toDouble() }).toFloat()
        check(norm > 0f)
        return FloatArray(vector.size) { index -> vector[index] / norm }
    }

    private fun unavailableException(availability: MemoryEmbeddingAvailability): IllegalStateException =
        IllegalStateException("Test embedding provider is not available: $availability")

    companion object {
        val TEST_DESCRIPTOR = MemoryEmbeddingDescriptor(
            providerId = "test-deterministic",
            runtimeVersion = "1",
            modelId = "test-hash-embedding",
            modelVersion = "1",
            modelSha256 = "0".repeat(64),
            dimension = 512,
            normalized = true,
            tokenizerVersion = "test-utf8-v1",
            tokenizerFingerprint = "1".repeat(64),
            maxInputTokens = 256,
            pooling = MemoryEmbeddingPooling.CLS,
            queryPrefix = "",
            documentPrefix = ""
        )

        private const val BYTE_MIDPOINT = 127.5f
    }
}
