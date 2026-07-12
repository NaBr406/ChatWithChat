package dev.chungjungsoo.gptmobile.data.memory.vector

import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpus
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpusChunk
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingDescriptor

interface MemoryVectorStore : AutoCloseable {
    fun readManifest(): MemoryVectorManifest?

    fun countChunks(): Long

    fun replaceSnapshot(snapshot: MemoryVectorSnapshot)

    fun query(request: MemoryVectorQuery): MemoryVectorQueryResult

    fun clearSnapshot()

    fun deleteDerivedStore()

    fun recoverFromCorruption(cause: Throwable): Boolean
}

data class MemoryVectorIndexIdentity(
    val corpus: MemoryCorpus,
    val sourcePath: String,
    val sourceHash: String,
    val corpusGeneration: Long,
    val indexFingerprint: String,
    val embeddingDescriptor: MemoryEmbeddingDescriptor,
    val chunkerVersion: String,
    val indexSchemaVersion: Int
)

data class MemoryVectorManifest(
    val identity: MemoryVectorIndexIdentity,
    val expectedChunkCount: Long,
    val completedAt: Long,
    val state: MemoryVectorManifestState
)

enum class MemoryVectorManifestState {
    BUILDING,
    READY
}

data class MemoryEmbeddedChunk(
    val chunk: MemoryCorpusChunk,
    val embedding: FloatArray
)

data class MemoryVectorSnapshot(
    val manifest: MemoryVectorManifest,
    val chunks: List<MemoryEmbeddedChunk>
)

data class MemoryVectorQuery(
    val expectedIdentity: MemoryVectorIndexIdentity,
    val embedding: FloatArray,
    val limit: Int
)

sealed interface MemoryVectorQueryResult {
    data class Ready(
        val manifest: MemoryVectorManifest,
        val matches: List<MemoryVectorMatch>
    ) : MemoryVectorQueryResult

    data class Unavailable(
        val reason: MemoryVectorUnavailableReason
    ) : MemoryVectorQueryResult
}

enum class MemoryVectorUnavailableReason {
    MISSING_MANIFEST,
    MANIFEST_NOT_READY,
    STALE_MANIFEST,
    CHUNK_COUNT_MISMATCH
}

data class MemoryVectorMatch(
    val chunk: MemoryCorpusChunk,
    val embedding: FloatArray,
    val cosineDistance: Float
)

class MemoryVectorStoreCorruptionException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
