package cn.nabr.chatwithchat.data.memory.embedding

import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxMemoryEmbeddingProviderTest {
    @Test
    fun `query applies prefix sends three padded INT64 inputs and L2 normalizes CLS`() = runBlocking {
        val tokenizer = RecordingTokenizer()
        val session = RecordingInferenceSession { _, _ -> floatArrayOf(3f, 4f, 0f) }
        val provider = provider(tokenizer, session)

        val embedding = provider.embedQuery("memory query").getOrThrow()

        assertEquals(listOf("Q:memory query"), tokenizer.texts)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f, 0f), embedding, 0.000001f)
        val batch = session.batches.single()
        assertEquals(1, batch.batchSize)
        assertEquals(batch.batchSize * batch.sequenceLength, batch.inputIds.size)
        assertEquals(batch.inputIds.size, batch.attentionMask.size)
        assertEquals(batch.inputIds.size, batch.tokenTypeIds.size)
        assertTrue(batch.inputIds.isNotEmpty())
    }

    @Test
    fun `documents preserve order use document prefix and split bounded batches`() = runBlocking {
        val tokenizer = RecordingTokenizer()
        val session = RecordingInferenceSession { callIndex, rowIndex ->
            floatArrayOf((callIndex * 2 + rowIndex + 1).toFloat(), 1f, 0f)
        }
        val provider = provider(tokenizer, session, maxBatchSize = 2)

        val embeddings = provider.embedDocuments(listOf("a", "longer", "c")).getOrThrow()

        assertEquals(listOf("D:a", "D:longer", "D:c"), tokenizer.texts)
        assertEquals(listOf(2, 1), session.batches.map { batch -> batch.batchSize })
        assertEquals(3, embeddings.size)
        assertArrayEquals(normalize(floatArrayOf(1f, 1f, 0f)), embeddings[0], 0.000001f)
        assertArrayEquals(normalize(floatArrayOf(2f, 1f, 0f)), embeddings[1], 0.000001f)
        assertArrayEquals(normalize(floatArrayOf(3f, 1f, 0f)), embeddings[2], 0.000001f)

        val firstBatch = session.batches.first()
        val firstAttentionEnd = tokenizer.lengths[0]
        assertTrue(
            firstBatch.attentionMask
                .slice(firstAttentionEnd until firstBatch.sequenceLength)
                .all { value -> value == 0L }
        )
    }

    @Test
    fun `invalid output dimension fails closed`() = runBlocking {
        val provider = OnnxMemoryEmbeddingProvider(
            descriptor = descriptor(),
            tokenizer = RecordingTokenizer(),
            inferenceSession = RecordingInferenceSession { _, _ -> floatArrayOf(1f, 2f) },
            inferenceDispatcher = Dispatchers.Unconfined
        )

        val result = provider.embedQuery("query")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("dimension"))
    }

    @Test
    fun `close is idempotent closes session and rejects later inference`() = runBlocking {
        val session = RecordingInferenceSession { _, _ -> floatArrayOf(1f, 0f, 0f) }
        val provider = provider(RecordingTokenizer(), session)

        provider.close()
        provider.close()

        assertEquals(1, session.closeCalls)
        assertTrue(provider.availability() is MemoryEmbeddingAvailability.Unavailable)
        assertTrue(provider.embedQuery("query").isFailure)
        assertTrue(provider.embedDocuments(listOf("document")).isFailure)
    }

    private fun provider(
        tokenizer: RecordingTokenizer,
        session: RecordingInferenceSession,
        maxBatchSize: Int = 8
    ) = OnnxMemoryEmbeddingProvider(
        descriptor = descriptor(),
        tokenizer = tokenizer,
        inferenceSession = session,
        inferenceDispatcher = Dispatchers.Unconfined,
        maxBatchSize = maxBatchSize
    )

    private fun descriptor() = MemoryEmbeddingDescriptor(
        providerId = "onnx-test",
        runtimeVersion = "1.27.0",
        modelId = "test-model",
        modelVersion = "test-revision",
        modelSha256 = "a".repeat(64),
        dimension = 3,
        normalized = true,
        tokenizerVersion = "test-tokenizer",
        tokenizerFingerprint = "b".repeat(64),
        maxInputTokens = 16,
        pooling = MemoryEmbeddingPooling.CLS,
        queryPrefix = "Q:",
        documentPrefix = "D:"
    )

    private fun normalize(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { value -> (value * value).toDouble() }).toFloat()
        return FloatArray(values.size) { index -> values[index] / norm }
    }

    private class RecordingTokenizer : MemoryEmbeddingTextTokenizer {
        val texts = mutableListOf<String>()
        val lengths = mutableListOf<Int>()

        override fun encode(text: String, maxTokens: Int): BertTokenizedInput {
            texts += text
            val size = (text.length + 2).coerceAtMost(maxTokens)
            lengths += size
            return BertTokenizedInput(
                inputIds = LongArray(size) { index -> (index + 100).toLong() },
                attentionMask = LongArray(size) { 1L },
                tokenTypeIds = LongArray(size)
            )
        }
    }

    private class RecordingInferenceSession(
        private val output: (callIndex: Int, rowIndex: Int) -> FloatArray
    ) : MemoryEmbeddingInferenceSession {
        val batches = mutableListOf<MemoryEmbeddingInferenceBatch>()
        var closeCalls = 0

        override fun run(batch: MemoryEmbeddingInferenceBatch): List<FloatArray> {
            val callIndex = batches.size
            batches += batch
            return List(batch.batchSize) { rowIndex -> output(callIndex, rowIndex) }
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
