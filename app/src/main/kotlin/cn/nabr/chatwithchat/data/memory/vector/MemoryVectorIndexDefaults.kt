package cn.nabr.chatwithchat.data.memory.vector

import cn.nabr.chatwithchat.data.memory.MemoryCorpus
import cn.nabr.chatwithchat.data.memory.embedding.ProductionMemoryEmbeddingArtifactContract

object MemoryVectorIndexDefaults {
    val embeddingDescriptor = ProductionMemoryEmbeddingArtifactContract.descriptor

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
