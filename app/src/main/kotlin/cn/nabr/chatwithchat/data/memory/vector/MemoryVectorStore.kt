package cn.nabr.chatwithchat.data.memory.vector

import cn.nabr.chatwithchat.data.memory.MemoryCorpus
import cn.nabr.chatwithchat.data.memory.MemoryCorpusChunk
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingDescriptor

interface MemoryVectorStore : AutoCloseable {
    fun readManifest(): MemoryVectorManifest?

    fun countChunks(): Long

    fun verifySnapshot(expectation: MemoryVectorSnapshotExpectation): MemoryVectorSnapshotVerification

    fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult

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

data class MemoryVectorSnapshotExpectation(
    val corpus: MemoryCorpus,
    val sourcePath: String,
    val sourceHash: String,
    val corpusGeneration: Long,
    val indexFingerprint: String,
    val chunks: List<MemoryCorpusChunk>
)

sealed interface MemoryVectorSnapshotVerification {
    data class Ready(val manifest: MemoryVectorManifest) : MemoryVectorSnapshotVerification

    data class Stale(val manifest: MemoryVectorManifest) : MemoryVectorSnapshotVerification

    data object Missing : MemoryVectorSnapshotVerification

    data object RecoveredCorruption : MemoryVectorSnapshotVerification
}

enum class MemoryVectorManifestState {
    BUILDING,
    READY
}

enum class MemoryVectorPublishResult {
    PUBLISHED,
    ALREADY_READY,
    SUPERSEDED
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

class MemoryVectorStoreConflictException(message: String) : IllegalStateException(message)
