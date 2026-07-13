package dev.chungjungsoo.gptmobile.data.memory.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OnnxMemoryEmbeddingProvider internal constructor(
    override val descriptor: MemoryEmbeddingDescriptor,
    private val tokenizer: MemoryEmbeddingTextTokenizer,
    private val inferenceSession: MemoryEmbeddingInferenceSession,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
) : MemoryEmbeddingProvider, AutoCloseable {
    private val lifecycleLock = ReentrantReadWriteLock()
    private val isClosed = AtomicBoolean(false)

    init {
        require(descriptor.pooling == MemoryEmbeddingPooling.CLS) { "The ONNX provider requires CLS pooling" }
        require(descriptor.normalized) { "The ONNX provider requires L2-normalized output" }
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
    }

    override suspend fun availability(): MemoryEmbeddingAvailability = if (isClosed.get()) {
        MemoryEmbeddingAvailability.Unavailable(
            MemoryEmbeddingAvailability.Reason.INITIALIZATION_FAILED,
            "The ONNX embedding provider is closed"
        )
    } else {
        MemoryEmbeddingAvailability.Available
    }

    override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> = embeddingResult {
        if (texts.isEmpty()) return@embeddingResult emptyList()
        texts.chunked(maxBatchSize).flatMap { batch ->
            embedBatch(batch.map { text -> descriptor.documentPrefix + text })
        }
    }

    override suspend fun embedQuery(text: String): Result<FloatArray> = embeddingResult {
        embedBatch(listOf(descriptor.queryPrefix + text)).single()
    }

    override fun close() {
        lifecycleLock.write {
            if (isClosed.compareAndSet(false, true)) inferenceSession.close()
        }
    }

    private suspend fun <T> embeddingResult(block: () -> T): Result<T> = try {
        Result.success(
            withContext(inferenceDispatcher) {
                lifecycleLock.read {
                    check(!isClosed.get()) { "The ONNX embedding provider is closed" }
                    block()
                }
            }
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun embedBatch(texts: List<String>): List<FloatArray> {
        val encoded = texts.map { text -> tokenizer.encode(text, descriptor.maxInputTokens) }
        val batch = MemoryEmbeddingInferenceBatch.from(encoded)
        val clsEmbeddings = inferenceSession.run(batch)
        check(clsEmbeddings.size == texts.size) {
            "ONNX output batch size ${clsEmbeddings.size} does not match input batch size ${texts.size}"
        }
        return clsEmbeddings.map { embedding -> embedding.normalized() }
    }

    private fun FloatArray.normalized(): FloatArray {
        check(size == descriptor.dimension) {
            "ONNX embedding dimension $size does not match ${descriptor.dimension}"
        }
        check(all(Float::isFinite)) { "ONNX embedding contains a non-finite value" }
        val norm = sqrt(sumOf { value -> (value * value).toDouble() }).toFloat()
        check(norm.isFinite() && norm > 0f) { "ONNX embedding has an invalid L2 norm" }
        return FloatArray(size) { index -> this[index] / norm }
    }

    companion object {
        private const val DEFAULT_MAX_BATCH_SIZE = 8

        fun create(
            artifacts: MemoryEmbeddingInstalledArtifacts,
            descriptor: MemoryEmbeddingDescriptor = ProductionMemoryEmbeddingArtifactContract.descriptor,
            maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE,
            inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default
        ): Result<OnnxMemoryEmbeddingProvider> = runCatching {
            require(descriptor == ProductionMemoryEmbeddingArtifactContract.descriptor) {
                "The production ONNX provider requires the pinned embedding artifact contract"
            }
            val tokenizer = BertWordPieceTokenizer.fromVocabFile(
                artifacts.vocabFile,
                descriptor.maxInputTokens
            )
            val environment = OrtEnvironment.getEnvironment()
            var session: OrtSession? = null
            try {
                OrtSession.SessionOptions().use { options ->
                    session = environment.createSession(artifacts.modelFile.absolutePath, options)
                }
                val inferenceSession = OrtMemoryEmbeddingInferenceSession(environment, checkNotNull(session))
                OnnxMemoryEmbeddingProvider(
                    descriptor = descriptor,
                    tokenizer = MemoryEmbeddingTextTokenizer { text, maxTokens ->
                        tokenizer.encode(text, maxTokens)
                    },
                    inferenceSession = inferenceSession,
                    inferenceDispatcher = inferenceDispatcher,
                    maxBatchSize = maxBatchSize
                )
            } catch (error: Throwable) {
                session?.close()
                throw error
            }
        }
    }
}

internal fun interface MemoryEmbeddingTextTokenizer {
    fun encode(text: String, maxTokens: Int): BertTokenizedInput
}

internal interface MemoryEmbeddingInferenceSession : AutoCloseable {
    fun run(batch: MemoryEmbeddingInferenceBatch): List<FloatArray>
}

internal data class MemoryEmbeddingInferenceBatch(
    val batchSize: Int,
    val sequenceLength: Int,
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(sequenceLength > 0) { "sequenceLength must be positive" }
        val expectedSize = batchSize * sequenceLength
        require(inputIds.size == expectedSize) { "inputIds size does not match the batch shape" }
        require(attentionMask.size == expectedSize) { "attentionMask size does not match the batch shape" }
        require(tokenTypeIds.size == expectedSize) { "tokenTypeIds size does not match the batch shape" }
    }

    companion object {
        fun from(inputs: List<BertTokenizedInput>): MemoryEmbeddingInferenceBatch {
            require(inputs.isNotEmpty()) { "inputs must not be empty" }
            val sequenceLength = inputs.maxOf { input -> input.inputIds.size }
            val flattenedSize = inputs.size * sequenceLength
            val inputIds = LongArray(flattenedSize)
            val attentionMask = LongArray(flattenedSize)
            val tokenTypeIds = LongArray(flattenedSize)
            inputs.forEachIndexed { batchIndex, input ->
                val offset = batchIndex * sequenceLength
                input.inputIds.copyInto(inputIds, destinationOffset = offset)
                input.attentionMask.copyInto(attentionMask, destinationOffset = offset)
                input.tokenTypeIds.copyInto(tokenTypeIds, destinationOffset = offset)
            }
            return MemoryEmbeddingInferenceBatch(
                batchSize = inputs.size,
                sequenceLength = sequenceLength,
                inputIds = inputIds,
                attentionMask = attentionMask,
                tokenTypeIds = tokenTypeIds
            )
        }
    }
}

private class OrtMemoryEmbeddingInferenceSession(
    private val environment: OrtEnvironment,
    private val session: OrtSession
) : MemoryEmbeddingInferenceSession {
    init {
        check(session.inputNames.containsAll(INPUT_NAMES)) {
            "The ONNX model does not expose the required three INT64 inputs"
        }
    }

    override fun run(batch: MemoryEmbeddingInferenceBatch): List<FloatArray> {
        val shape = longArrayOf(batch.batchSize.toLong(), batch.sequenceLength.toLong())
        val inputIdTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.inputIds), shape)
        val attentionMaskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.attentionMask), shape)
        val tokenTypeTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.tokenTypeIds), shape)
        try {
            session.run(
                mapOf(
                    INPUT_IDS to inputIdTensor,
                    ATTENTION_MASK to attentionMaskTensor,
                    TOKEN_TYPE_IDS to tokenTypeTensor
                )
            ).use { output ->
                @Suppress("UNCHECKED_CAST")
                val lastHiddenState = output[0].value as? Array<Array<FloatArray>>
                    ?: error("The ONNX model did not return a rank-3 float hidden state")
                check(lastHiddenState.size == batch.batchSize) {
                    "The ONNX hidden-state batch size does not match its input"
                }
                return lastHiddenState.map { tokenEmbeddings ->
                    check(tokenEmbeddings.isNotEmpty()) { "The ONNX model returned no CLS token" }
                    tokenEmbeddings[0].copyOf()
                }
            }
        } finally {
            tokenTypeTensor.close()
            attentionMaskTensor.close()
            inputIdTensor.close()
        }
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val TOKEN_TYPE_IDS = "token_type_ids"
        val INPUT_NAMES = setOf(INPUT_IDS, ATTENTION_MASK, TOKEN_TYPE_IDS)
    }
}
