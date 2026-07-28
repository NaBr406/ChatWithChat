package cn.nabr.chatwithchat.data.memory

import java.security.MessageDigest

enum class MemoryCorpus {
    CHAT_RECALL_LONG_TERM,
    MAINTENANCE_WORKING_SET
}

enum class MemoryProjectionPolicy {
    CHAT_ACTIVE_ONLY,
    MAINTENANCE_FULL
}

val MemoryCorpus.projectionPolicy: MemoryProjectionPolicy
    get() = when (this) {
        MemoryCorpus.CHAT_RECALL_LONG_TERM -> MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        MemoryCorpus.MAINTENANCE_WORKING_SET -> MemoryProjectionPolicy.MAINTENANCE_FULL
    }

data class MemoryProjectionDiagnostic(
    val code: String,
    val sourcePath: String,
    val count: Int = 1
)

data class MemoryChunkingResult(
    val chunks: List<MemoryCorpusChunk>,
    val projectionHash: String,
    val diagnostics: List<MemoryProjectionDiagnostic> = emptyList()
)

data class MemoryCorpusChunk(
    val chunkId: String,
    val entryId: String?,
    val sourcePath: String,
    val chunkIndex: Int,
    val heading: String?,
    val text: String,
    val type: String?,
    val sensitivity: String?,
    val source: String?,
    val chatId: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val canonicalKey: String? = null,
    val scope: String? = null,
    val validity: String? = null,
    val recallState: String? = null,
    val lastObservedAt: Long = updatedAt,
    val supersededBy: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val extraMetadata: Map<String, String> = emptyMap(),
    val embeddingText: String = text,
    val embeddingContentHash: String = embeddingText.sha256Utf8(),
    val rankingHash: String = embeddingContentHash
)

data class MemoryCorpusSnapshot(
    val corpus: MemoryCorpus,
    val sourcePath: String,
    val canonicalSourceHash: String,
    val recallProjectionHash: String = canonicalSourceHash,
    val generation: Long,
    val chunks: List<MemoryCorpusChunk>,
    val diagnostics: List<MemoryProjectionDiagnostic> = emptyList()
)

interface MemoryCorpusSnapshotSource {
    suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>>

    suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean>

    suspend fun isProjectionCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> = isCurrent(snapshots)
}

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun String.sha256Utf8(): String = toByteArray(Charsets.UTF_8).sha256Hex()
