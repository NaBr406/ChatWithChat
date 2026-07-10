package dev.chungjungsoo.gptmobile.data.memory

interface MemoryRetriever {
    suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>>
}

data class MemoryRetrievalRequest(
    val query: String,
    val recentContext: String? = null,
    val limit: Int = 8,
    val candidateLimit: Int = 200,
    val tokenBudget: Int = 900,
    val includePrivate: Boolean = true,
    val sourcePath: String? = null,
    val strategy: MemoryRetrievalStrategy = MemoryRetrievalStrategy.LEXICAL
)

data class MemoryRetrievalResult(
    val chunkId: String,
    val entryId: String?,
    val sourcePath: String,
    val text: String,
    val type: String?,
    val sensitivity: String?,
    val source: String?,
    val contentHash: String,
    val lexicalScore: Float? = null,
    val vectorScore: Float? = null,
    val fusedScore: Float,
    val updatedAt: Long
)

enum class MemoryRetrievalStrategy {
    LEXICAL,
    VECTOR,
    HYBRID
}

data class MemoryRetrievalConfig(
    val strategy: MemoryRetrievalStrategy = MemoryRetrievalStrategy.LEXICAL,
    val lexicalWeight: Float = 1f,
    val vectorWeight: Float = 0f
)
