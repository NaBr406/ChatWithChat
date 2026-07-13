package dev.chungjungsoo.gptmobile.data.memory.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionMemoryEmbeddingArtifactContractTest {
    @Test
    fun `production contract pins the verified model tokenizer and inference identity`() {
        val contract = ProductionMemoryEmbeddingArtifactContract

        assertEquals("1.27.0", contract.ONNX_RUNTIME_VERSION)
        assertEquals("Xenova/bge-small-zh-v1.5", contract.MODEL_ID)
        assertEquals("75c43b069aac4d136ba6bc1122f995fedcfd2781", contract.MODEL_REVISION)
        assertEquals("15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc", contract.MODEL_SHA256)
        assertEquals("BAAI/bge-small-zh-v1.5", contract.TOKENIZER_ID)
        assertEquals("7999e1d3359715c523056ef9478215996d62a620", contract.TOKENIZER_REVISION)
        assertEquals(512, contract.descriptor.dimension)
        assertEquals(256, contract.descriptor.maxInputTokens)
        assertEquals(MemoryEmbeddingPooling.CLS, contract.descriptor.pooling)
        assertTrue(contract.descriptor.normalized)
        assertEquals("\u4e3a\u8fd9\u4e2a\u53e5\u5b50\u751f\u6210\u8868\u793a\u4ee5\u7528\u4e8e\u68c0\u7d22\u76f8\u5173\u6587\u7ae0\uff1a", contract.descriptor.queryPrefix)
        assertEquals("", contract.descriptor.documentPrefix)
    }

    @Test
    fun `production contract pins every packaged artifact by size and checksum`() {
        val artifacts = ProductionMemoryEmbeddingArtifactContract.artifacts.associateBy { it.relativePath }

        assertEquals(7, artifacts.size)
        assertEquals(24_010_842L, artifacts.getValue("model.onnx").sizeBytes)
        assertEquals(
            "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26",
            artifacts.getValue("tokenizer.json").sha256
        )
        assertEquals(
            "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a",
            artifacts.getValue("tokenizer_config.json").sha256
        )
        assertEquals(
            "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
            artifacts.getValue("vocab.txt").sha256
        )
    }
}
