package dev.chungjungsoo.gptmobile.data.memory.embedding

data class MemoryEmbeddingDescriptor(
    val providerId: String,
    val runtimeVersion: String,
    val modelId: String,
    val modelVersion: String,
    val modelSha256: String,
    val dimension: Int,
    val normalized: Boolean,
    val tokenizerVersion: String,
    val tokenizerFingerprint: String,
    val maxInputTokens: Int,
    val pooling: MemoryEmbeddingPooling,
    val queryPrefix: String,
    val documentPrefix: String
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(runtimeVersion.isNotBlank()) { "runtimeVersion must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(modelVersion.isNotBlank()) { "modelVersion must not be blank" }
        require(modelSha256.matches(SHA_256_REGEX)) { "modelSha256 must be a lowercase SHA-256 value" }
        require(dimension > 0) { "dimension must be positive" }
        require(tokenizerVersion.isNotBlank()) { "tokenizerVersion must not be blank" }
        require(tokenizerFingerprint.matches(SHA_256_REGEX)) {
            "tokenizerFingerprint must be a lowercase SHA-256 value"
        }
        require(maxInputTokens > 0) { "maxInputTokens must be positive" }
    }

    private companion object {
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}

enum class MemoryEmbeddingPooling {
    CLS,
    MEAN
}
