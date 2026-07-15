package cn.nabr.chatwithchat.data.memory

import java.security.MessageDigest

enum class MemoryCorpus {
    CHAT_RECALL_LONG_TERM,
    MAINTENANCE_WORKING_SET
}

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
    val contentHash: String
)

data class MemoryCorpusSnapshot(
    val corpus: MemoryCorpus,
    val sourcePath: String,
    val sourceHash: String,
    val generation: Long,
    val chunks: List<MemoryCorpusChunk>
)

interface MemoryCorpusSnapshotSource {
    suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>>

    suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean>
}

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun String.sha256Utf8(): String = toByteArray(Charsets.UTF_8).sha256Hex()
