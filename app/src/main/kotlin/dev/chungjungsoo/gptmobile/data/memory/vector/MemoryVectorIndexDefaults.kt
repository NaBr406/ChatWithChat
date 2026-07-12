package dev.chungjungsoo.gptmobile.data.memory.vector

import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpus
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingDescriptor
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingPooling

object MemoryVectorIndexDefaults {
    val embeddingDescriptor = MemoryEmbeddingDescriptor(
        providerId = "onnx-runtime-android",
        runtimeVersion = "1.27.0",
        modelId = "Xenova/bge-small-zh-v1.5",
        modelVersion = "75c43b069aac4d136ba6bc1122f995fedcfd2781",
        modelSha256 = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc",
        dimension = MEMORY_VECTOR_DIMENSION,
        normalized = true,
        tokenizerVersion = "BAAI/bge-small-zh-v1.5@7999e1d3359715c523056ef9478215996d62a620",
        tokenizerFingerprint = "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26",
        maxInputTokens = 256,
        pooling = MemoryEmbeddingPooling.CLS,
        queryPrefix = "\u4e3a\u8fd9\u4e2a\u53e5\u5b50\u751f\u6210\u8868\u793a\u4ee5\u7528\u4e8e\u68c0\u7d22\u76f8\u5173\u6587\u7ae0\uff1a",
        documentPrefix = ""
    )

    val configuration = MemoryVectorIndexConfiguration(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        indexSchemaVersion = MEMORY_VECTOR_INDEX_SCHEMA_VERSION,
        chunkerVersion = "memory-chunker-v1",
        maxChunkChars = 1_200,
        chunkOverlapChars = 0,
        markdownCodecVersion = "markdown-memory-codec-v1",
        embeddingDescriptor = embeddingDescriptor,
        queryTextNormalization = "provider-prefix-then-utf8-v1",
        documentTextNormalization = "utf8-markdown-chunk-v1",
        distanceMetric = MemoryVectorDistanceMetric.COSINE
    )
}
