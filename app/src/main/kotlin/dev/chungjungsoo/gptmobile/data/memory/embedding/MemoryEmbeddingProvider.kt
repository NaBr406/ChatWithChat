package dev.chungjungsoo.gptmobile.data.memory.embedding

interface MemoryEmbeddingProvider {
    val descriptor: MemoryEmbeddingDescriptor

    suspend fun availability(): MemoryEmbeddingAvailability

    suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>>

    suspend fun embedQuery(text: String): Result<FloatArray>
}
