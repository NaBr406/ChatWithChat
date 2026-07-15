package cn.nabr.chatwithchat.data.memory.vector

import cn.nabr.chatwithchat.data.memory.MemoryCorpus
import cn.nabr.chatwithchat.data.memory.MemoryFilePaths
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingDescriptor
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingPooling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVectorIndexConfigurationTest {
    @Test
    fun `fingerprint is deterministic and changes with retrieval meaning`() {
        val baseline = configuration()
        val baselineFingerprint = baseline.fingerprint()
        val variants = listOf(
            baseline.copy(corpus = MemoryCorpus.MAINTENANCE_WORKING_SET),
            baseline.copy(indexSchemaVersion = 2),
            baseline.copy(chunkerVersion = "chunker-v2"),
            baseline.copy(maxChunkChars = 800),
            baseline.copy(chunkOverlapChars = 32),
            baseline.copy(markdownCodecVersion = "markdown-v2"),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(providerId = "provider-v2")),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(runtimeVersion = "runtime-v2")),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(modelVersion = "model-v2")),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(modelSha256 = "c".repeat(64))),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(dimension = 384)),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(normalized = false)),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(tokenizerVersion = "tokenizer-v2")),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(tokenizerFingerprint = "d".repeat(64))),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(maxInputTokens = 512)),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(pooling = MemoryEmbeddingPooling.MEAN)),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(queryPrefix = "query: ")),
            baseline.copy(embeddingDescriptor = DESCRIPTOR.copy(documentPrefix = "document: ")),
            baseline.copy(queryTextNormalization = "unicode-nfkc-lowercase"),
            baseline.copy(documentTextNormalization = "unicode-nfkc")
        )

        assertEquals(baselineFingerprint, configuration().fingerprint())
        assertEquals(64, baselineFingerprint.length)
        assertTrue(variants.all { variant -> variant.fingerprint() != baselineFingerprint })
    }

    @Test
    fun `identity keeps source freshness separate from index fingerprint`() {
        val configuration = configuration()
        val first = configuration.identity(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            sourceHash = "e".repeat(64),
            corpusGeneration = 4
        )
        val nextGeneration = configuration.identity(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            sourceHash = "e".repeat(64),
            corpusGeneration = 5
        )

        assertEquals(configuration.fingerprint(), first.indexFingerprint)
        assertNotEquals(first, nextGeneration)
    }

    private fun configuration(): MemoryVectorIndexConfiguration = MemoryVectorIndexConfiguration(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        indexSchemaVersion = MEMORY_VECTOR_INDEX_SCHEMA_VERSION,
        chunkerVersion = "chunker-v1",
        maxChunkChars = 1200,
        chunkOverlapChars = 0,
        markdownCodecVersion = "markdown-v1",
        embeddingDescriptor = DESCRIPTOR,
        queryTextNormalization = "trim-collapse-whitespace",
        documentTextNormalization = "trim-collapse-whitespace",
        distanceMetric = MemoryVectorDistanceMetric.COSINE
    )

    private companion object {
        val DESCRIPTOR = MemoryEmbeddingDescriptor(
            providerId = "provider-v1",
            runtimeVersion = "runtime-v1",
            modelId = "model",
            modelVersion = "model-v1",
            modelSha256 = "a".repeat(64),
            dimension = MEMORY_VECTOR_DIMENSION,
            normalized = true,
            tokenizerVersion = "tokenizer-v1",
            tokenizerFingerprint = "b".repeat(64),
            maxInputTokens = 256,
            pooling = MemoryEmbeddingPooling.CLS,
            queryPrefix = "",
            documentPrefix = ""
        )
    }
}
