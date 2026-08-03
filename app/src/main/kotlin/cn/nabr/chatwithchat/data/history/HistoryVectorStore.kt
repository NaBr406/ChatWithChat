package cn.nabr.chatwithchat.data.history

import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryEmbeddingEntity
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import java.security.MessageDigest
import kotlin.math.sqrt

interface HistoryVectorStore {
    suspend fun publish(projections: List<ChatHistoryTurnProjection>): Result<HistoryVectorPublication>

    suspend fun query(query: String, limit: Int): Result<List<HistoryVectorHit>>
}

data class HistoryVectorPublication(
    val generation: Long,
    val projectionHash: String,
    val descriptorHash: String,
    val count: Int
)

data class HistoryVectorHit(
    val turnKey: String,
    val score: Float
)

class RoomHistoryVectorStore(
    private val dao: ChatHistoryDao,
    private val capabilitySource: MemoryEmbeddingCapabilitySource
) : HistoryVectorStore {
    override suspend fun publish(projections: List<ChatHistoryTurnProjection>): Result<HistoryVectorPublication> = runCatching {
        val ready = capabilitySource.current() as? MemoryEmbeddingCapability.Ready
            ?: error("history_embedding_unavailable")
        val descriptorHash = descriptorHash(ready)
        val existing = dao.getEmbeddings(descriptorHash).associateBy { it.turnKey }
        val missing = projections.filter { projection ->
            val cached = existing[projection.turnKey]
            cached == null || cached.contentHash != projection.contentHash
        }
        val vectors = if (missing.isEmpty()) {
            emptyList()
        } else {
            ready.provider.embedDocuments(missing.map(::embeddingText)).getOrThrow()
        }
        require(vectors.size == missing.size) { "history_embedding_count_mismatch" }
        val now = System.currentTimeMillis() / 1000
        if (missing.isNotEmpty()) {
            dao.upsertEmbeddings(
                missing.zip(vectors).map { (projection, vector) ->
                    ChatHistoryEmbeddingEntity(
                        turnKey = projection.turnKey,
                        contentHash = projection.contentHash,
                        descriptorHash = descriptorHash,
                        embeddingJson = encodeVector(vector),
                        updatedAt = now
                    )
                }
            )
        }
        dao.deleteOrphanedEmbeddings()
        val projectionHash = hashProjectionSet(projections)
        HistoryVectorPublication(
            generation = now,
            projectionHash = projectionHash,
            descriptorHash = descriptorHash,
            count = projections.size
        )
    }

    override suspend fun query(query: String, limit: Int): Result<List<HistoryVectorHit>> = runCatching {
        val ready = capabilitySource.current() as? MemoryEmbeddingCapability.Ready
            ?: error("history_embedding_unavailable")
        val queryVector = ready.provider.embedQuery(query).getOrThrow()
        val descriptorHash = descriptorHash(ready)
        val cached = dao.getEmbeddings(descriptorHash)
        val decoded = cached.mapNotNull { item ->
            decodeVector(item.embeddingJson)?.let { vector -> item to vector }
        }
        val corruptKeys = cached.map(ChatHistoryEmbeddingEntity::turnKey).toSet() - decoded.map { (item, _) -> item.turnKey }.toSet()
        if (corruptKeys.isNotEmpty()) dao.deleteEmbeddings(corruptKeys.toList())
        decoded
            .mapNotNull { (cachedItem, vector) ->
                val score = cosine(queryVector, vector)
                score.takeIf { it.isFinite() }?.let { HistoryVectorHit(cachedItem.turnKey, it) }
            }
            .sortedByDescending(HistoryVectorHit::score)
            .take(limit.coerceAtLeast(0))
    }

    private fun embeddingText(projection: ChatHistoryTurnProjection): String = buildString {
        append(projection.title)
        append('\n')
        append(projection.userContent)
        append('\n')
        append(projection.assistantContent)
    }

    private fun descriptorHash(capability: MemoryEmbeddingCapability.Ready): String = sha256(
        listOf(
            capability.provider.descriptor.providerId,
            capability.provider.descriptor.runtimeVersion,
            capability.provider.descriptor.modelId,
            capability.provider.descriptor.modelVersion,
            capability.provider.descriptor.modelSha256,
            capability.provider.descriptor.dimension.toString(),
            capability.provider.descriptor.tokenizerVersion,
            capability.provider.descriptor.tokenizerFingerprint
        ).joinToString("\n")
    )

    private fun hashProjectionSet(projections: List<ChatHistoryTurnProjection>): String = sha256(
        projections.sortedBy(ChatHistoryTurnProjection::turnKey)
            .joinToString("\n") { projection -> "${projection.turnKey}:${projection.contentHash}" }
    )

    private fun encodeVector(vector: FloatArray): String = vector.joinToString(",")

    private fun decodeVector(value: String): FloatArray? {
        if (value.isBlank()) return null
        val parts = value.split(',')
        if (parts.any { it.toFloatOrNull() == null }) return null
        return parts.map(String::toFloat).toFloatArray().takeIf { it.isNotEmpty() }
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || left.size != right.size) return Float.NaN
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        val denominator = sqrt(leftNorm) * sqrt(rightNorm)
        return if (denominator == 0.0) Float.NaN else (dot / denominator).toFloat()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
