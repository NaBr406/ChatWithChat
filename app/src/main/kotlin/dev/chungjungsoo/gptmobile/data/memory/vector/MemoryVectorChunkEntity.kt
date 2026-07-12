package dev.chungjungsoo.gptmobile.data.memory.vector

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique
import io.objectbox.annotation.VectorDistanceType

const val MEMORY_VECTOR_DIMENSION = 512
const val MEMORY_VECTOR_HNSW_DIMENSIONS = 512L
const val MEMORY_VECTOR_INDEX_SCHEMA_VERSION = 1

@Entity
data class MemoryVectorChunkEntity(
    @Id var objectBoxId: Long = 0,
    @Unique var chunkId: String = "",
    var entryId: String? = null,
    var sourcePath: String = "",
    var chunkIndex: Int = 0,
    var heading: String? = null,
    var text: String = "",
    var type: String? = null,
    var sensitivity: String? = null,
    var source: String? = null,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
    var contentHash: String = "",
    var sourceHash: String = "",
    var corpusGeneration: Long = 0,
    var indexFingerprint: String = "",
    var embeddingModelId: String = "",
    var embeddingModelVersion: String = "",
    var embeddingDimension: Int = MEMORY_VECTOR_DIMENSION,
    var chunkerVersion: String = "",
    var indexSchemaVersion: Int = MEMORY_VECTOR_INDEX_SCHEMA_VERSION,
    @HnswIndex(dimensions = MEMORY_VECTOR_HNSW_DIMENSIONS, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null
)
