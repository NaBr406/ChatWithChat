package cn.nabr.chatwithchat.data.history

import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryEmbeddingCacheEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryVectorEntryEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryVectorSnapshotEntity
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction

data class HistoryVectorCandidate(
    val turnKey: String,
    val score: Float
)

interface HistoryVectorStore {
    suspend fun publish(projections: List<ChatHistoryProjectionEntity>): Result<HistoryVectorPublishResult>

    suspend fun search(query: String, limit: Int): List<HistoryVectorCandidate>
}

class RoomHistoryVectorStore(
    private val database: ChatDatabaseV2,
    private val historyDao: ChatHistoryDao,
    private val capabilitySource: MemoryEmbeddingCapabilitySource
) : HistoryVectorStore {
    override suspend fun publish(projections: List<ChatHistoryProjectionEntity>): Result<HistoryVectorPublishResult> {
        val state = historyDao.indexState(HISTORY_INDEX_STATE_ID)
            ?: ChatHistoryIndexStateEntity(
                stateId = HISTORY_INDEX_STATE_ID,
                projectionGeneration = 0L,
                vectorStatus = HistoryVectorStatus.MISSING,
                updatedAt = System.currentTimeMillis() / 1000
            )
        if (projections.isEmpty()) {
            val now = System.currentTimeMillis() / 1000
            database.withTransaction {
                historyDao.deleteVectorEntries(HISTORY_VECTOR_SNAPSHOT_ID)
                historyDao.upsertVectorSnapshot(
                    ChatHistoryVectorSnapshotEntity(
                        snapshotId = HISTORY_VECTOR_SNAPSHOT_ID,
                        generation = state.projectionGeneration,
                        projectionHash = state.projectionHash.orEmpty(),
                        descriptorHash = "",
                        dimension = 0,
                        chunkerVersion = HISTORY_CHUNKER_VERSION,
                        indexSchemaVersion = HISTORY_VECTOR_INDEX_SCHEMA_VERSION,
                        expectedCount = 0,
                        publishedAt = now
                    )
                )
                historyDao.upsertIndexState(
                    state.copy(
                        vectorPublishedGeneration = state.projectionGeneration,
                        vectorStatus = HistoryVectorStatus.READY,
                        updatedAt = now
                    )
                )
            }
            return Result.success(
                HistoryVectorPublishResult(
                    generation = state.projectionGeneration,
                    projectionHash = state.projectionHash.orEmpty(),
                    embeddedCount = 0,
                    reusedCount = 0
                )
            )
        }
        val capability = capabilitySource.current()
        if (capability !is MemoryEmbeddingCapability.Ready) {
            return Result.failure(IllegalStateException("history_embedding_unavailable"))
        }
        val descriptorHash = descriptorHash(capability.provider.descriptor)
        val projectionHash = state.projectionHash ?: hashProjections(projections)
        val cached = projections.mapNotNull { projection ->
            historyDao.findEmbedding(projection.turnKey, projection.contentHash, descriptorHash)
                ?.takeIf { it.dimension == capability.provider.descriptor.dimension }
                ?.let { embedding ->
                    runCatching { embedding.embedding.toFloatArray(embedding.dimension) }
                        .getOrNull()
                        ?.let { vector -> projection.turnKey to vector }
                }
        }.toMap()
        val missing = projections.filterNot { it.turnKey in cached }
        val freshEmbeddings = if (missing.isEmpty()) {
            emptyList()
        } else {
            capability.provider.embedDocuments(missing.map(::embeddingText)).getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                return Result.failure(throwable)
            }
        }
        if (freshEmbeddings.size != missing.size) {
            return Result.failure(IllegalStateException("history_embedding_count_mismatch"))
        }
        val embeddingsByKey = cached.toMutableMap()
        missing.zip(freshEmbeddings).forEach { (projection, embedding) ->
            if (embedding.size != capability.provider.descriptor.dimension) {
                return Result.failure(IllegalStateException("history_embedding_dimension_mismatch"))
            }
            embeddingsByKey[projection.turnKey] = embedding
        }
        val now = System.currentTimeMillis() / 1000
        database.withTransaction {
            missing.forEach { projection ->
                historyDao.upsertEmbedding(
                    ChatHistoryEmbeddingCacheEntity(
                        turnKey = projection.turnKey,
                        contentHash = projection.contentHash,
                        descriptorHash = descriptorHash,
                        embedding = embeddingsByKey.getValue(projection.turnKey).toByteArray(),
                        dimension = capability.provider.descriptor.dimension,
                        updatedAt = now
                    )
                )
            }
            historyDao.deleteVectorEntries(HISTORY_VECTOR_SNAPSHOT_ID)
            historyDao.upsertVectorSnapshot(
                ChatHistoryVectorSnapshotEntity(
                    snapshotId = HISTORY_VECTOR_SNAPSHOT_ID,
                    generation = state.projectionGeneration,
                    projectionHash = projectionHash,
                    descriptorHash = descriptorHash,
                    dimension = capability.provider.descriptor.dimension,
                    chunkerVersion = HISTORY_CHUNKER_VERSION,
                    indexSchemaVersion = HISTORY_VECTOR_INDEX_SCHEMA_VERSION,
                    expectedCount = projections.size,
                    publishedAt = now
                )
            )
            historyDao.upsertVectorEntries(
                projections.map { projection ->
                    ChatHistoryVectorEntryEntity(
                        snapshotId = HISTORY_VECTOR_SNAPSHOT_ID,
                        turnKey = projection.turnKey,
                        generation = state.projectionGeneration,
                        contentHash = projection.contentHash,
                        descriptorHash = descriptorHash,
                        dimension = capability.provider.descriptor.dimension,
                        embedding = embeddingsByKey.getValue(projection.turnKey).toByteArray()
                    )
                }
            )
            historyDao.upsertIndexState(
                state.copy(
                    projectionHash = projectionHash,
                    vectorPublishedGeneration = state.projectionGeneration,
                    vectorStatus = HistoryVectorStatus.READY,
                    updatedAt = now
                )
            )
        }
        return Result.success(
            HistoryVectorPublishResult(
                generation = state.projectionGeneration,
                projectionHash = projectionHash,
                embeddedCount = freshEmbeddings.size,
                reusedCount = cached.size
            )
        )
    }

    override suspend fun search(query: String, limit: Int): List<HistoryVectorCandidate> {
        val capability = capabilitySource.current()
        if (capability !is MemoryEmbeddingCapability.Ready) return emptyList()
        val state = historyDao.indexState(HISTORY_INDEX_STATE_ID) ?: return emptyList()
        val snapshot = historyDao.vectorSnapshot(HISTORY_VECTOR_SNAPSHOT_ID) ?: return emptyList()
        if (
            state.vectorStatus != HistoryVectorStatus.READY ||
            state.vectorPublishedGeneration != state.projectionGeneration ||
            snapshot.generation != state.projectionGeneration ||
            snapshot.projectionHash != state.projectionHash ||
            snapshot.descriptorHash != descriptorHash(capability.provider.descriptor)
        ) return emptyList()
        val queryEmbedding = capability.provider.embedQuery(query).getOrElse { return emptyList() }
        if (queryEmbedding.size != snapshot.dimension) return emptyList()
        val entries = historyDao.vectorEntries(HISTORY_VECTOR_SNAPSHOT_ID)
        if (
            snapshot.indexSchemaVersion != HISTORY_VECTOR_INDEX_SCHEMA_VERSION ||
            snapshot.chunkerVersion != HISTORY_CHUNKER_VERSION ||
            snapshot.expectedCount < 0 ||
            entries.size != snapshot.expectedCount
        ) return emptyList()
        val freshEntries = entries.filter { entry ->
            entry.generation == snapshot.generation &&
                entry.descriptorHash == snapshot.descriptorHash &&
                entry.dimension == snapshot.dimension &&
                historyDao.findProjection(entry.turnKey)?.let { projection ->
                    projection.eligibilityState == HistoryEligibilityState.ELIGIBLE &&
                        projection.contentHash == entry.contentHash
                } == true
        }
        if (freshEntries.size != entries.size) return emptyList()
        return freshEntries
            .mapNotNull { entry ->
                if (entry.dimension != snapshot.dimension) return@mapNotNull null
                val embedding = runCatching { entry.embedding.toFloatArray(entry.dimension) }.getOrNull()
                    ?: return@mapNotNull null
                val score = cosine(queryEmbedding, embedding)
                if (score.isFinite()) HistoryVectorCandidate(entry.turnKey, score) else null
            }
            .sortedByDescending { it.score }
            .take(limit.coerceIn(1, 16))
    }

    private fun embeddingText(projection: ChatHistoryProjectionEntity): String =
        listOf(projection.title, "User: ${projection.userContent}", "Assistant: ${projection.assistantContent}")
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private fun descriptorHash(descriptor: cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingDescriptor): String =
        sha256(
            listOf(
                descriptor.providerId,
                descriptor.runtimeVersion,
                descriptor.modelId,
                descriptor.modelVersion,
                descriptor.modelSha256,
                descriptor.dimension.toString(),
                descriptor.tokenizerVersion,
                descriptor.tokenizerFingerprint,
                descriptor.pooling.name,
                descriptor.queryPrefix,
                descriptor.documentPrefix
            ).joinToString("\u0000")
        )

    private fun hashProjections(projections: List<ChatHistoryProjectionEntity>): String =
        sha256(projections.sortedBy { it.turnKey }.joinToString("\n") { "${it.turnKey}:${it.contentHash}" })

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun FloatArray.toByteArray(): ByteArray = ByteBuffer.allocate(size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { forEach(::putFloat) }
        .array()

    private fun ByteArray.toFloatArray(dimension: Int): FloatArray {
        require(size == dimension * Float.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size || left.isEmpty()) return Float.NaN
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
        return (dot / kotlin.math.sqrt(leftNorm * rightNorm)).toFloat()
    }
}

class NoOpHistoryVectorStore : HistoryVectorStore {
    override suspend fun publish(projections: List<ChatHistoryProjectionEntity>): Result<HistoryVectorPublishResult> =
        Result.failure(IllegalStateException("history_embedding_unavailable"))

    override suspend fun search(query: String, limit: Int): List<HistoryVectorCandidate> = emptyList()
}

private const val HISTORY_CHUNKER_VERSION = "history-turn-v1"

data class HistoryVectorPublishResult(
    val generation: Long,
    val projectionHash: String,
    val embeddedCount: Int,
    val reusedCount: Int
)
