package cn.nabr.chatwithchat.data.history

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingArtifactInstallResult
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingArtifactInstaller
import cn.nabr.chatwithchat.data.memory.embedding.OnnxMemoryEmbeddingProvider
import cn.nabr.chatwithchat.data.memory.embedding.ProductionMemoryEmbeddingArtifactContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class HistoryProductionEmbeddingInstrumentedTest {
    @Test
    fun productionArtifactEmbedsHistoryTextAndReportsDeviceLatency() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installResult = MemoryEmbeddingArtifactInstaller.fromContext(context).install()
        val artifacts = when (installResult) {
            is MemoryEmbeddingArtifactInstallResult.Success -> installResult.artifacts
            is MemoryEmbeddingArtifactInstallResult.NotProvisioned -> {
                error("Production embedding artifact unavailable: ${installResult.availability.reason}")
            }
        }
        assertEquals(
            ProductionMemoryEmbeddingArtifactContract.MODEL_SHA256,
            ProductionMemoryEmbeddingArtifactContract.descriptor.modelSha256
        )

        val provider = OnnxMemoryEmbeddingProvider.create(artifacts).getOrThrow()
        try {
            assertTrue(provider.availability().isAvailable())

            val query = provider.embedQuery("历史索引延迟验证").getOrThrow()
            assertEmbeddingContract(query)

            // Warm the tokenizer/session before collecting the device-facing latency sample.
            provider.embedQuery("历史索引暖机").getOrThrow()
            val queryTimes = buildList {
                repeat(3) {
                    var embedding: FloatArray? = null
                    val elapsed = measureTimeMillis {
                        embedding = provider.embedQuery("历史索引延迟验证 $it").getOrThrow()
                    }
                    assertEmbeddingContract(checkNotNull(embedding))
                    add(elapsed)
                }
            }
            var documentBatch: List<FloatArray> = emptyList()
            val documentMillis = measureTimeMillis {
                documentBatch = provider.embedDocuments(
                    listOf(
                        "历史项目包含迁移与回滚证据。",
                        "历史提示快照必须保持稳定。"
                    )
                ).getOrThrow()
            }
            assertEquals(2, documentBatch.size)
            documentBatch.forEach(::assertEmbeddingContract)

            val p95 = queryTimes.sorted()[(queryTimes.size * 95 + 99) / 100 - 1]
            assertTrue("ONNX query p95 exceeded 5 seconds: $queryTimes", p95 <= 5_000)
            assertTrue("ONNX document batch exceeded 5 seconds: $documentMillis", documentMillis <= 5_000)
            Log.i(
                LOG_TAG,
                "production_artifact model=${ProductionMemoryEmbeddingArtifactContract.MODEL_ID} " +
                    "dimension=${ProductionMemoryEmbeddingArtifactContract.EMBEDDING_DIMENSION} " +
                    "queryMs=$queryTimes queryP95Ms=$p95 documentBatchMs=$documentMillis"
            )
        } finally {
            provider.close()
        }
    }

    private fun assertEmbeddingContract(embedding: FloatArray) {
        assertEquals(ProductionMemoryEmbeddingArtifactContract.EMBEDDING_DIMENSION, embedding.size)
        assertTrue(embedding.all(Float::isFinite))
        val squaredNorm = embedding.sumOf { value -> (value * value).toDouble() }
        assertTrue(abs(squaredNorm - 1.0) < 0.0001)
    }

    private fun cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability.isAvailable(): Boolean =
        this is cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability.Available

    private companion object {
        const val LOG_TAG = "HistoryEmbeddingDevice"
    }
}
