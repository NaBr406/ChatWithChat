package dev.chungjungsoo.gptmobile.data.memory.vector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxRuntimeBuildCanaryInstrumentedTest {
    @Test
    fun pinnedInt8Model_createsSessionAndProducesNormalizedClsEmbedding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val assetPath = "memory-model/bge-small-zh-v1.5/model.onnx"
        val hasProvisionedModel = runCatching {
            targetContext.assets.open(assetPath).close()
        }.isSuccess
        assumeTrue("Run tools/memory-model/provision-bge-small-zh-v1.5-production.ps1 first", hasProvisionedModel)

        val modelFile = File(targetContext.cacheDir, "bge-small-zh-v1.5-int8.onnx")
        targetContext.assets.open(assetPath).use { input ->
            modelFile.outputStream().use(input::copyTo)
        }
        assertEquals(EXPECTED_MODEL_SHA256, modelFile.sha256())

        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { sessionOptions ->
            environment.createSession(modelFile.absolutePath, sessionOptions).use { session ->
                val inputIds = longArrayOf(101, 872, 1962, 102)
                val shape = longArrayOf(1, inputIds.size.toLong())
                val tensors = listOf(
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), shape),
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(inputIds.size) { 1 }), shape),
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(inputIds.size)), shape)
                )
                tensors.useAll { inputIdTensor, attentionMaskTensor, tokenTypeTensor ->
                    session.run(
                        mapOf(
                            "input_ids" to inputIdTensor,
                            "attention_mask" to attentionMaskTensor,
                            "token_type_ids" to tokenTypeTensor
                        )
                    ).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val hiddenState = output[0].value as Array<Array<FloatArray>>
                        val normalizedCls = hiddenState[0][0].normalized()
                        assertEquals(MEMORY_VECTOR_DIMENSION, normalizedCls.size)
                        assertTrue(normalizedCls.all(Float::isFinite))
                        assertTrue(kotlin.math.abs(normalizedCls.sumOf { (it * it).toDouble() } - 1.0) < 0.0001)
                    }
                }
            }
        }
    }

    private fun FloatArray.normalized(): FloatArray {
        val norm = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
        require(norm > 0f)
        return FloatArray(size) { index -> this[index] / norm }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private inline fun <T : AutoCloseable> List<T>.useAll(block: (T, T, T) -> Unit) {
        require(size == 3)
        try {
            block(this[0], this[1], this[2])
        } finally {
            forEach(AutoCloseable::close)
        }
    }

    private companion object {
        const val EXPECTED_MODEL_SHA256 = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"
    }
}
