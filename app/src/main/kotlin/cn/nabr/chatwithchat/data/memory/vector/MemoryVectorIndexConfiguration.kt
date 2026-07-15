package cn.nabr.chatwithchat.data.memory.vector

import cn.nabr.chatwithchat.data.memory.MemoryCorpus
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingDescriptor
import cn.nabr.chatwithchat.data.memory.sha256Utf8

data class MemoryVectorIndexConfiguration(
    val corpus: MemoryCorpus,
    val indexSchemaVersion: Int,
    val chunkerVersion: String,
    val maxChunkChars: Int,
    val chunkOverlapChars: Int,
    val markdownCodecVersion: String,
    val embeddingDescriptor: MemoryEmbeddingDescriptor,
    val queryTextNormalization: String,
    val documentTextNormalization: String,
    val distanceMetric: MemoryVectorDistanceMetric
) {
    init {
        require(indexSchemaVersion > 0) { "indexSchemaVersion must be positive" }
        require(chunkerVersion.isNotBlank()) { "chunkerVersion must not be blank" }
        require(maxChunkChars > 0) { "maxChunkChars must be positive" }
        require(chunkOverlapChars in 0 until maxChunkChars) {
            "chunkOverlapChars must be non-negative and smaller than maxChunkChars"
        }
        require(markdownCodecVersion.isNotBlank()) { "markdownCodecVersion must not be blank" }
        require(queryTextNormalization.isNotBlank()) { "queryTextNormalization must not be blank" }
        require(documentTextNormalization.isNotBlank()) { "documentTextNormalization must not be blank" }
    }

    fun fingerprint(): String = buildString {
        appendFingerprintField("fingerprintFormat", FINGERPRINT_FORMAT)
        appendFingerprintField("corpus", corpus.name)
        appendFingerprintField("indexSchemaVersion", indexSchemaVersion.toString())
        appendFingerprintField("chunkerVersion", chunkerVersion)
        appendFingerprintField("maxChunkChars", maxChunkChars.toString())
        appendFingerprintField("chunkOverlapChars", chunkOverlapChars.toString())
        appendFingerprintField("markdownCodecVersion", markdownCodecVersion)
        appendFingerprintField("embeddingProviderId", embeddingDescriptor.providerId)
        appendFingerprintField("embeddingRuntimeVersion", embeddingDescriptor.runtimeVersion)
        appendFingerprintField("embeddingModelId", embeddingDescriptor.modelId)
        appendFingerprintField("embeddingModelVersion", embeddingDescriptor.modelVersion)
        appendFingerprintField("embeddingModelSha256", embeddingDescriptor.modelSha256)
        appendFingerprintField("embeddingDimension", embeddingDescriptor.dimension.toString())
        appendFingerprintField("embeddingNormalized", embeddingDescriptor.normalized.toString())
        appendFingerprintField("tokenizerVersion", embeddingDescriptor.tokenizerVersion)
        appendFingerprintField("tokenizerFingerprint", embeddingDescriptor.tokenizerFingerprint)
        appendFingerprintField("embeddingMaxInputTokens", embeddingDescriptor.maxInputTokens.toString())
        appendFingerprintField("embeddingPooling", embeddingDescriptor.pooling.name)
        appendFingerprintField("queryPrefix", embeddingDescriptor.queryPrefix)
        appendFingerprintField("documentPrefix", embeddingDescriptor.documentPrefix)
        appendFingerprintField("queryTextNormalization", queryTextNormalization)
        appendFingerprintField("documentTextNormalization", documentTextNormalization)
        appendFingerprintField("distanceMetric", distanceMetric.name)
    }.sha256Utf8()

    fun identity(
        sourcePath: String,
        sourceHash: String,
        corpusGeneration: Long
    ): MemoryVectorIndexIdentity = MemoryVectorIndexIdentity(
        corpus = corpus,
        sourcePath = sourcePath,
        sourceHash = sourceHash,
        corpusGeneration = corpusGeneration,
        indexFingerprint = fingerprint(),
        embeddingDescriptor = embeddingDescriptor,
        chunkerVersion = chunkerVersion,
        indexSchemaVersion = indexSchemaVersion
    )

    private fun StringBuilder.appendFingerprintField(name: String, value: String) {
        append(name.length)
        append(':')
        append(name)
        append(value.length)
        append(':')
        append(value)
    }

    private companion object {
        const val FINGERPRINT_FORMAT = "memory-vector-index-fingerprint-v1"
    }
}

enum class MemoryVectorDistanceMetric {
    COSINE
}
