package dev.chungjungsoo.gptmobile.data.memory.embedding

/** Immutable production identity for the on-device memory embedding runtime. */
object ProductionMemoryEmbeddingArtifactContract {
    const val ONNX_RUNTIME_VERSION = "1.27.0"
    const val MODEL_ID = "Xenova/bge-small-zh-v1.5"
    const val MODEL_REVISION = "75c43b069aac4d136ba6bc1122f995fedcfd2781"
    const val MODEL_SHA256 = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"
    const val TOKENIZER_ID = "BAAI/bge-small-zh-v1.5"
    const val TOKENIZER_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
    const val EMBEDDING_DIMENSION = 512
    const val MAX_INPUT_TOKENS = 256
    const val QUERY_PREFIX = "\u4e3a\u8fd9\u4e2a\u53e5\u5b50\u751f\u6210\u8868\u793a\u4ee5\u7528\u4e8e\u68c0\u7d22\u76f8\u5173\u6587\u7ae0\uff1a"
    const val DOCUMENT_PREFIX = ""
    const val ASSET_ROOT = "memory-model/bge-small-zh-v1.5"
    const val INSTALL_ROOT = "memory_models/bge-small-zh-v1.5"

    val descriptor = MemoryEmbeddingDescriptor(
        providerId = "onnx-runtime-android",
        runtimeVersion = ONNX_RUNTIME_VERSION,
        modelId = MODEL_ID,
        modelVersion = MODEL_REVISION,
        modelSha256 = MODEL_SHA256,
        dimension = EMBEDDING_DIMENSION,
        normalized = true,
        tokenizerVersion = "$TOKENIZER_ID@$TOKENIZER_REVISION",
        tokenizerFingerprint = TOKENIZER_JSON_SHA256,
        maxInputTokens = MAX_INPUT_TOKENS,
        pooling = MemoryEmbeddingPooling.CLS,
        queryPrefix = QUERY_PREFIX,
        documentPrefix = DOCUMENT_PREFIX
    )

    val artifacts = listOf(
        MemoryEmbeddingArtifact(
            relativePath = MODEL_FILE,
            sizeBytes = 24_010_842L,
            sha256 = MODEL_SHA256
        ),
        MemoryEmbeddingArtifact(
            relativePath = "quantize_config.json",
            sizeBytes = 674L,
            sha256 = "2cc488b20fa05fe86aba2fdc2be44d24827e11e2b7c7a0753d1427da6797b46f"
        ),
        MemoryEmbeddingArtifact(
            relativePath = "MODEL_CARD.md",
            sizeBytes = 27_670L,
            sha256 = "c48a4eeea77f6b1d38b48ec1c5b8d4f86d5550cc43fa345a0db1b2ca1d082369"
        ),
        MemoryEmbeddingArtifact(
            relativePath = "config.json",
            sizeBytes = 776L,
            sha256 = "3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a"
        ),
        MemoryEmbeddingArtifact(
            relativePath = TOKENIZER_JSON_FILE,
            sizeBytes = 439_125L,
            sha256 = TOKENIZER_JSON_SHA256
        ),
        MemoryEmbeddingArtifact(
            relativePath = TOKENIZER_CONFIG_FILE,
            sizeBytes = 367L,
            sha256 = TOKENIZER_CONFIG_SHA256
        ),
        MemoryEmbeddingArtifact(
            relativePath = VOCAB_FILE,
            sizeBytes = 109_540L,
            sha256 = VOCAB_SHA256
        )
    )

    const val MODEL_FILE = "model.onnx"
    const val TOKENIZER_JSON_FILE = "tokenizer.json"
    const val TOKENIZER_CONFIG_FILE = "tokenizer_config.json"
    const val VOCAB_FILE = "vocab.txt"
    const val TOKENIZER_JSON_SHA256 = "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26"
    const val TOKENIZER_CONFIG_SHA256 = "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a"
    const val VOCAB_SHA256 = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
}

data class MemoryEmbeddingArtifact(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String
) {
    init {
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        require(!relativePath.startsWith('/') && !relativePath.startsWith('\\')) {
            "relativePath must be relative"
        }
        require(relativePath.split('/', '\\').none { segment -> segment == ".." }) {
            "relativePath must not traverse parent directories"
        }
        require(sizeBytes >= 0L) { "sizeBytes must not be negative" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be a lowercase SHA-256 value" }
    }
}
