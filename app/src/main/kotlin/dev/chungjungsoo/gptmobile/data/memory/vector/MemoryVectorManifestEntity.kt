package dev.chungjungsoo.gptmobile.data.memory.vector

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique

const val MEMORY_VECTOR_MANIFEST_KEY = "current"

@Entity
data class MemoryVectorManifestEntity(
    @Id var objectBoxId: Long = 0,
    @Unique var manifestKey: String = MEMORY_VECTOR_MANIFEST_KEY,
    var state: String = MemoryVectorManifestState.BUILDING.name,
    var corpus: String = "",
    var sourcePath: String = "",
    var sourceHash: String = "",
    var corpusGeneration: Long = 0,
    var indexFingerprint: String = "",
    var expectedChunkCount: Long = 0,
    var completedAt: Long = 0,
    var embeddingProviderId: String = "",
    var embeddingRuntimeVersion: String = "",
    var embeddingModelId: String = "",
    var embeddingModelVersion: String = "",
    var embeddingModelSha256: String = "",
    var embeddingDimension: Int = MEMORY_VECTOR_DIMENSION,
    var embeddingNormalized: Boolean = false,
    var tokenizerVersion: String = "",
    var tokenizerFingerprint: String = "",
    var embeddingMaxInputTokens: Int = 0,
    var embeddingPooling: String = "",
    var queryPrefix: String = "",
    var documentPrefix: String = "",
    var chunkerVersion: String = "",
    var indexSchemaVersion: Int = MEMORY_VECTOR_INDEX_SCHEMA_VERSION
)
