package dev.chungjungsoo.gptmobile.data.memory.embedding

import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEmbeddingProviderTest {
    @Test
    fun `descriptor rejects values that cannot safely fingerprint an index`() {
        assertFailsWithIllegalArgument {
            descriptor(dimension = 0)
        }
        assertFailsWithIllegalArgument {
            descriptor(modelSha256 = "not-a-checksum")
        }
        assertFailsWithIllegalArgument {
            descriptor(tokenizerFingerprint = "not-a-checksum")
        }
        assertFailsWithIllegalArgument {
            descriptor(maxInputTokens = 0)
        }
    }

    @Test
    fun `deterministic fake preserves order dimension and normalization`() = runBlocking {
        val provider = DeterministicFakeMemoryEmbeddingProvider()

        val first = provider.embedDocuments(listOf("alpha", "beta", "alpha")).getOrThrow()
        val second = provider.embedDocuments(listOf("alpha", "beta", "alpha")).getOrThrow()

        assertEquals(3, first.size)
        first.zip(second).forEach { (left, right) ->
            assertArrayEquals(left, right, 0f)
            assertEquals(provider.descriptor.dimension, left.size)
            assertTrue(left.all(Float::isFinite))
            assertTrue(abs(left.sumOf { value -> (value * value).toDouble() } - 1.0) < 0.000001)
        }
        assertArrayEquals(first[0], first[2], 0f)
        assertFalse(first[0].contentEquals(first[1]))
    }

    @Test
    fun `deterministic fake applies query and document prefixes independently`() = runBlocking {
        val descriptor = descriptor(queryPrefix = "query:", documentPrefix = "document:")
        val provider = DeterministicFakeMemoryEmbeddingProvider(descriptor)

        val query = provider.embedQuery("same text").getOrThrow()
        val document = provider.embedDocuments(listOf("same text")).getOrThrow().single()

        assertNotEquals(query.toList(), document.toList())
    }

    @Test
    fun `deterministic fake fails closed while unavailable`() = runBlocking {
        val unavailable = MemoryEmbeddingAvailability.Unavailable(
            MemoryEmbeddingAvailability.Reason.ARTIFACT_MISSING,
            "test model missing"
        )
        val provider = DeterministicFakeMemoryEmbeddingProvider(availabilityState = unavailable)

        assertEquals(unavailable, provider.availability())
        assertTrue(provider.embedQuery("query").isFailure)
        assertTrue(provider.embedDocuments(listOf("document")).isFailure)
    }

    private fun descriptor(
        dimension: Int = 8,
        modelSha256: String = "a".repeat(64),
        tokenizerFingerprint: String = "b".repeat(64),
        maxInputTokens: Int = 256,
        queryPrefix: String = "",
        documentPrefix: String = ""
    ): MemoryEmbeddingDescriptor = MemoryEmbeddingDescriptor(
        providerId = "test-provider",
        runtimeVersion = "1.0",
        modelId = "test-model",
        modelVersion = "revision-1",
        modelSha256 = modelSha256,
        dimension = dimension,
        normalized = true,
        tokenizerVersion = "tokenizer-v1",
        tokenizerFingerprint = tokenizerFingerprint,
        maxInputTokens = maxInputTokens,
        pooling = MemoryEmbeddingPooling.CLS,
        queryPrefix = queryPrefix,
        documentPrefix = documentPrefix
    )

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
