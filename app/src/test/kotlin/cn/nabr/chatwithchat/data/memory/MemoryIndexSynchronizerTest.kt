package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.dao.MemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.dao.MemoryVectorIndexPublicationOutcome
import cn.nabr.chatwithchat.data.database.dao.MemoryVectorIndexPublicationRequest
import cn.nabr.chatwithchat.data.database.entity.MemoryCorpusState
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.MemoryMutationGroup
import cn.nabr.chatwithchat.data.database.entity.MemoryMutationReceipt
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingDescriptor
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingPooling
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingProvider
import cn.nabr.chatwithchat.data.memory.vector.MemoryEmbeddedChunk
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorDistanceMetric
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexConfiguration
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorManifest
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorManifestState
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorPublishResult
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorQuery
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorQueryResult
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshot
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshotExpectation
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorSnapshotVerification
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorStore
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryIndexSynchronizerTest {

    @Test
    fun `invalid payload fails terminal before touching vector dependencies`() = runBlocking {
        val fixture = fixture()

        val result = fixture.synchronizer().synchronize(
            fixture.job.copy(payloadJson = "{not-json")
        )

        assertEquals(MemoryIndexSyncResult.Terminal("invalid_vector_index_payload"), result)
        assertEquals(0, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
    }

    @Test
    fun `newer Room corpus supersedes stale job without publishing`() = runBlocking {
        val fixture = fixture(roomGeneration = GENERATION + 1)

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(MemoryIndexSyncResult.Superseded, result)
        assertEquals(0, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
    }

    @Test
    fun `matching ready manifest fast forwards Room while embedding capability is unavailable`() = runBlocking {
        val fixture = fixture()
        fixture.vectorStore.installReadySnapshot(fixture.readyVectorSnapshot())
        val capability = MemoryEmbeddingCapability.Unavailable(
            MemoryEmbeddingAvailability.Unavailable(
                MemoryEmbeddingAvailability.Reason.ARTIFACT_MISSING,
                "model is intentionally absent"
            )
        )

        val result = fixture.synchronizer(capability).synchronize(fixture.job)

        assertEquals(MemoryIndexSyncResult.Succeeded, result)
        assertEquals(0, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
        assertRoomPublicationCompleted(fixture)
    }

    @Test
    fun `unavailable provider blocks publication`() = runBlocking {
        val fixture = fixture(
            availability = MemoryEmbeddingAvailability.Unavailable(
                MemoryEmbeddingAvailability.Reason.ARTIFACT_MISSING,
                "model file missing"
            )
        )

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(MemoryIndexSyncResult.BlockedDependency("embedding_unavailable:artifact_missing"), result)
        assertEquals(1, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
        assertRoomPublicationBlocked(fixture, "embedding_unavailable:artifact_missing")
    }

    @Test
    fun `loading provider retries without publishing`() = runBlocking {
        val fixture = fixture(availability = MemoryEmbeddingAvailability.Loading)

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(MemoryIndexSyncResult.Retryable("embedding_provider_loading"), result)
        assertEquals(1, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
        assertRoomPublicationPending(fixture)
    }

    @Test
    fun `valid embeddings publish snapshot then complete Room receipt and group`() = runBlocking {
        val fixture = fixture()

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(MemoryIndexSyncResult.Succeeded, result)
        assertEquals(1, fixture.provider.embedDocumentCalls)
        assertEquals(listOf(fixture.snapshot.chunks.map(MemoryCorpusChunk::text)), fixture.provider.documentBatches)
        assertEquals(1, fixture.vectorStore.publishCalls)
        assertEquals(fixture.snapshot.chunks.size.toLong(), fixture.vectorStore.countChunks())
        assertEquals(fixture.expectedIdentity(), fixture.vectorStore.readManifest()?.identity)
        assertRoomPublicationCompleted(fixture)
    }

    @Test
    fun `ObjectBox publication survives Room completion failure without reembedding on retry`() = runBlocking {
        val backingDao = InMemoryMemoryRecoveryDao()
        val failOnceDao = FailFirstPublicationCompletionDao(backingDao)
        val fixture = fixture(recoveryDao = failOnceDao)
        val synchronizer = fixture.synchronizer()

        val first = synchronizer.synchronize(fixture.job)

        assertEquals(MemoryIndexSyncResult.Retryable("vector_index_sync_failed:IllegalStateException"), first)
        assertEquals(1, fixture.provider.embedDocumentCalls)
        assertEquals(1, fixture.vectorStore.publishCalls)
        assertRoomPublicationPending(fixture)

        val second = synchronizer.synchronize(fixture.job)

        assertEquals(MemoryIndexSyncResult.Succeeded, second)
        assertEquals(1, fixture.provider.embedDocumentCalls)
        assertEquals(1, fixture.vectorStore.publishCalls)
        assertEquals(2, failOnceDao.completionCalls)
        assertRoomPublicationCompleted(fixture)
    }

    @Test
    fun `embedding count mismatch blocks dependency without publishing`() = runBlocking {
        val fixture = fixture(
            embeddingResult = { texts ->
                Result.success(texts.dropLast(1).map { UNIT_VECTOR.copyOf() })
            }
        )

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(
            MemoryIndexSyncResult.BlockedDependency("embedding_provider_returned_invalid_vectors"),
            result
        )
        assertEquals(1, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
        assertRoomPublicationBlocked(fixture, "embedding_provider_returned_invalid_vectors")
    }

    @Test
    fun `embedding dimension mismatch blocks dependency without publishing`() = runBlocking {
        val fixture = fixture(
            embeddingResult = { texts ->
                Result.success(texts.map { floatArrayOf(1f, 0f, 0f) })
            }
        )

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(
            MemoryIndexSyncResult.BlockedDependency("embedding_provider_returned_invalid_vectors"),
            result
        )
        assertEquals(1, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
        assertRoomPublicationBlocked(fixture, "embedding_provider_returned_invalid_vectors")
    }

    @Test
    fun `non finite embedding blocks dependency without publishing`() = runBlocking {
        val fixture = fixture(
            embeddingResult = { texts ->
                Result.success(texts.map { floatArrayOf(Float.NaN, 0f, 0f, 1f) })
            }
        )

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(
            MemoryIndexSyncResult.BlockedDependency("embedding_provider_returned_invalid_vectors"),
            result
        )
        assertEquals(1, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
        assertRoomPublicationBlocked(fixture, "embedding_provider_returned_invalid_vectors")
    }

    @Test
    fun `Markdown change during embedding retries without publishing stale vectors`() = runBlocking {
        val fixture = fixture(isCurrentAfterEmbedding = false)

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(
            MemoryIndexSyncResult.Retryable("canonical_memory_changed_during_embedding"),
            result
        )
        assertEquals(1, fixture.provider.embedDocumentCalls)
        assertEquals(1, fixture.snapshotSource.currentChecks)
        assertEquals(0, fixture.vectorStore.publishCalls)
        assertRoomPublicationPending(fixture)
    }

    @Test
    fun `same generation conflicting manifest fails terminal without embedding or publishing`() = runBlocking {
        val fixture = fixture()
        fixture.vectorStore.installReadySnapshot(
            fixture.readyVectorSnapshot(sourceHash = "f".repeat(64))
        )

        val result = fixture.synchronizer().synchronize(fixture.job)

        assertEquals(
            MemoryIndexSyncResult.Terminal("same_generation_vector_manifest_conflict"),
            result
        )
        assertEquals(0, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.provider.embedDocumentCalls)
        assertEquals(0, fixture.vectorStore.publishCalls)
        assertRoomPublicationPending(fixture)
    }

    private suspend fun fixture(
        roomGeneration: Long = GENERATION,
        availability: MemoryEmbeddingAvailability = MemoryEmbeddingAvailability.Available,
        embeddingResult: suspend (List<String>) -> Result<List<FloatArray>> = { texts ->
            Result.success(texts.map { UNIT_VECTOR.copyOf() })
        },
        isCurrentAfterEmbedding: Boolean = true,
        recoveryDao: MemoryRecoveryDao = InMemoryMemoryRecoveryDao()
    ): Fixture {
        val configuration = configuration()
        val sourceHash = "a".repeat(64)
        val payload = MemoryIndexSyncJobPayload(
            mutationGroupId = GROUP_ID,
            receiptId = RECEIPT_ID,
            generation = GENERATION,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            sourceHash = sourceHash,
            targetIndexFingerprint = configuration.fingerprint()
        )
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = payload.sourcePath,
            sourceHash = payload.sourceHash,
            generation = 7,
            chunks = listOf(
                chunk(chunkId = "preference", chunkIndex = 0, text = "Prefers concise answers"),
                chunk(chunkId = "project", chunkIndex = 1, text = "Works on ChatWithChat")
            )
        )
        val provider = ScriptedEmbeddingProvider(
            availabilityState = availability,
            embeddingResult = embeddingResult
        )
        val snapshotSource = FakeSnapshotSource(
            snapshot = snapshot,
            isCurrentResult = isCurrentAfterEmbedding
        )
        val vectorStore = FakeMemoryVectorStore()
        val fileStore = MemoryFileStore(
            paths = MemoryFilePaths(Files.createTempDirectory("memory-index-synchronizer").toFile()),
            clock = FIXED_CLOCK
        )
        val fixture = Fixture(
            recoveryDao = recoveryDao,
            configuration = configuration,
            payload = payload,
            snapshot = snapshot,
            provider = provider,
            snapshotSource = snapshotSource,
            vectorStore = vectorStore,
            fileStore = fileStore
        )
        fixture.seedRoomState(roomGeneration)
        return fixture
    }

    private fun configuration(): MemoryVectorIndexConfiguration = MemoryVectorIndexConfiguration(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        indexSchemaVersion = 1,
        chunkerVersion = "test-chunker-v1",
        maxChunkChars = 1_200,
        chunkOverlapChars = 0,
        markdownCodecVersion = "test-markdown-v1",
        embeddingDescriptor = TEST_DESCRIPTOR,
        queryTextNormalization = "test-query-v1",
        documentTextNormalization = "test-document-v1",
        distanceMetric = MemoryVectorDistanceMetric.COSINE
    )

    private fun chunk(
        chunkId: String,
        chunkIndex: Int,
        text: String
    ): MemoryCorpusChunk = MemoryCorpusChunk(
        chunkId = chunkId,
        entryId = chunkId,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        chunkIndex = chunkIndex,
        heading = "Test",
        text = text,
        type = "preference",
        sensitivity = "normal",
        source = "test",
        chatId = null,
        createdAt = 1,
        updatedAt = 1,
        contentHash = text.sha256Utf8()
    )

    private suspend fun assertRoomPublicationCompleted(fixture: Fixture) {
        val corpus = fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)
        assertEquals(MemoryCorpusIndexStatus.READY, corpus?.indexStatus)
        assertEquals(GENERATION, corpus?.indexedGeneration)
        assertEquals(fixture.payload.sourceHash, corpus?.indexedSourceHash)
        assertEquals(fixture.payload.targetIndexFingerprint, corpus?.indexedFingerprint)
        assertEquals(MemoryMutationState.INDEXED, fixture.recoveryDao.getMutationReceipt(RECEIPT_ID)?.state)
        assertEquals(MemoryMutationState.INDEXED, fixture.recoveryDao.getMutationGroup(GROUP_ID)?.state)
    }

    private suspend fun assertRoomPublicationPending(fixture: Fixture) {
        val corpus = fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)
        assertEquals(MemoryCorpusIndexStatus.PENDING, corpus?.indexStatus)
        assertNull(corpus?.indexedGeneration)
        assertNull(corpus?.indexedSourceHash)
        assertNull(corpus?.indexedFingerprint)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.recoveryDao.getMutationReceipt(RECEIPT_ID)?.state)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.recoveryDao.getMutationGroup(GROUP_ID)?.state)
    }

    private suspend fun assertRoomPublicationBlocked(
        fixture: Fixture,
        reason: String
    ) {
        val corpus = fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)
        assertEquals(MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY, corpus?.indexStatus)
        assertEquals(reason, corpus?.lastError)
        assertNull(corpus?.indexedGeneration)
        assertNull(corpus?.indexedSourceHash)
        assertNull(corpus?.indexedFingerprint)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.recoveryDao.getMutationReceipt(RECEIPT_ID)?.state)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.recoveryDao.getMutationGroup(GROUP_ID)?.state)
    }

    private data class Fixture(
        val recoveryDao: MemoryRecoveryDao,
        val configuration: MemoryVectorIndexConfiguration,
        val payload: MemoryIndexSyncJobPayload,
        val snapshot: MemoryCorpusSnapshot,
        val provider: ScriptedEmbeddingProvider,
        val snapshotSource: FakeSnapshotSource,
        val vectorStore: FakeMemoryVectorStore,
        val fileStore: MemoryFileStore
    ) {
        val job = MemoryMaintenanceJob(
            jobId = "index-job-1",
            type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
            status = MemoryMaintenanceJobStatus.RUNNING,
            idempotencyKey = "index-job-1",
            payloadJson = Json.encodeToString(payload),
            attempts = 1,
            lastError = null,
            createdAt = FIXED_TIME,
            startedAt = FIXED_TIME,
            updatedAt = FIXED_TIME,
            nextRunAt = null,
            family = MemoryMaintenanceJobFamily.INDEX,
            generation = payload.generation
        )

        suspend fun seedRoomState(roomGeneration: Long) {
            recoveryDao.insertMutationGroup(
                MemoryMutationGroup(
                    groupId = payload.mutationGroupId,
                    generation = payload.generation,
                    semanticJobId = "semantic-job-1",
                    semanticBatchId = "semantic-batch-1",
                    state = MemoryMutationState.INDEX_PENDING,
                    idempotencyKey = "group-idempotency",
                    lastError = null,
                    createdAt = FIXED_TIME,
                    updatedAt = FIXED_TIME,
                    completedAt = null,
                    expectedReceiptCount = 1
                )
            )
            recoveryDao.insertMutationReceipts(
                listOf(
                    MemoryMutationReceipt(
                        receiptId = payload.receiptId,
                        groupId = payload.mutationGroupId,
                        generation = payload.generation,
                        sourcePath = payload.sourcePath,
                        baseSourceHash = "b".repeat(64),
                        targetSourceHash = payload.sourceHash,
                        stagedTargetPath = ".staging/group-1_receipt-1.target",
                        state = MemoryMutationState.INDEX_PENDING,
                        idempotencyKey = "receipt-idempotency",
                        targetIndexFingerprint = payload.targetIndexFingerprint,
                        attempts = 0,
                        lastError = null,
                        createdAt = FIXED_TIME,
                        updatedAt = FIXED_TIME,
                        fileCommittedAt = FIXED_TIME,
                        indexedAt = null
                    )
                )
            )
            recoveryDao.insertCorpusStateIgnore(
                MemoryCorpusState(
                    corpus = CHAT_RECALL_CORPUS_KEY,
                    sourcePath = payload.sourcePath,
                    sourceHash = payload.sourceHash,
                    generation = roomGeneration,
                    targetIndexFingerprint = payload.targetIndexFingerprint,
                    indexStatus = MemoryCorpusIndexStatus.PENDING,
                    indexedGeneration = null,
                    indexedSourceHash = null,
                    indexedFingerprint = null,
                    latestReceiptId = payload.receiptId,
                    lastError = null,
                    createdAt = FIXED_TIME,
                    updatedAt = FIXED_TIME
                )
            )
        }

        fun synchronizer(
            capability: MemoryEmbeddingCapability = MemoryEmbeddingCapability.Ready(provider, configuration)
        ): MemoryIndexSynchronizer = MemoryIndexSynchronizer(
            recoveryDao = recoveryDao,
            snapshotSource = snapshotSource,
            memoryFileStore = fileStore,
            vectorStore = vectorStore,
            embeddingCapabilitySource = MemoryEmbeddingCapabilitySource { capability },
            clock = FIXED_CLOCK
        )

        fun expectedIdentity(sourceHash: String = payload.sourceHash) = configuration.identity(
            sourcePath = payload.sourcePath,
            sourceHash = sourceHash,
            corpusGeneration = payload.generation
        )

        fun readyVectorSnapshot(sourceHash: String = payload.sourceHash): MemoryVectorSnapshot = MemoryVectorSnapshot(
            manifest = MemoryVectorManifest(
                identity = expectedIdentity(sourceHash),
                expectedChunkCount = snapshot.chunks.size.toLong(),
                completedAt = FIXED_TIME,
                state = MemoryVectorManifestState.READY
            ),
            chunks = snapshot.chunks.map { chunk ->
                MemoryEmbeddedChunk(chunk = chunk, embedding = UNIT_VECTOR.copyOf())
            }
        )
    }

    private class FakeSnapshotSource(
        var snapshot: MemoryCorpusSnapshot,
        var isCurrentResult: Boolean
    ) : MemoryCorpusSnapshotSource {
        var currentChecks = 0

        override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
            Result.success(listOf(snapshot))

        override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> {
            currentChecks += 1
            return Result.success(isCurrentResult)
        }
    }

    private class ScriptedEmbeddingProvider(
        var availabilityState: MemoryEmbeddingAvailability,
        private val embeddingResult: suspend (List<String>) -> Result<List<FloatArray>>
    ) : MemoryEmbeddingProvider {
        override val descriptor: MemoryEmbeddingDescriptor = TEST_DESCRIPTOR
        var availabilityCalls = 0
        var embedDocumentCalls = 0
        val documentBatches = mutableListOf<List<String>>()

        override suspend fun availability(): MemoryEmbeddingAvailability {
            availabilityCalls += 1
            return availabilityState
        }

        override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> {
            embedDocumentCalls += 1
            documentBatches += texts
            return embeddingResult(texts)
        }

        override suspend fun embedQuery(text: String): Result<FloatArray> =
            error("Query embedding is not used by the index synchronizer")
    }

    private class FakeMemoryVectorStore : MemoryVectorStore {
        private var manifest: MemoryVectorManifest? = null
        private var chunks: List<MemoryEmbeddedChunk> = emptyList()
        var publishCalls = 0

        override fun readManifest(): MemoryVectorManifest? = manifest

        override fun countChunks(): Long = chunks.size.toLong()

        override fun verifySnapshot(
            expectation: MemoryVectorSnapshotExpectation
        ): MemoryVectorSnapshotVerification {
            val current = manifest ?: return MemoryVectorSnapshotVerification.Missing
            val identity = current.identity
            val matches = identity.corpus == expectation.corpus &&
                identity.sourcePath == expectation.sourcePath &&
                identity.sourceHash == expectation.sourceHash &&
                identity.corpusGeneration == expectation.corpusGeneration &&
                identity.indexFingerprint == expectation.indexFingerprint
            if (!matches) return MemoryVectorSnapshotVerification.Stale(current)
            return if (
                chunks.size.toLong() == current.expectedChunkCount &&
                chunks.map(MemoryEmbeddedChunk::chunk) == expectation.chunks
            ) {
                MemoryVectorSnapshotVerification.Ready(current)
            } else {
                MemoryVectorSnapshotVerification.RecoveredCorruption
            }
        }

        override fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult {
            publishCalls += 1
            manifest = snapshot.manifest
            chunks = snapshot.chunks
            return MemoryVectorPublishResult.PUBLISHED
        }

        override fun query(request: MemoryVectorQuery): MemoryVectorQueryResult =
            error("Vector query is not used by the index synchronizer")

        override fun clearSnapshot() {
            manifest = null
            chunks = emptyList()
        }

        override fun deleteDerivedStore() = clearSnapshot()

        override fun recoverFromCorruption(cause: Throwable): Boolean = false

        override fun close() = Unit

        fun installReadySnapshot(snapshot: MemoryVectorSnapshot) {
            manifest = snapshot.manifest
            chunks = snapshot.chunks
        }
    }

    private class FailFirstPublicationCompletionDao(
        private val delegate: MemoryRecoveryDao
    ) : MemoryRecoveryDao by delegate {
        var completionCalls = 0

        override suspend fun completeVectorIndexPublication(
            request: MemoryVectorIndexPublicationRequest
        ): MemoryVectorIndexPublicationOutcome {
            completionCalls += 1
            if (completionCalls == 1) {
                error("injected Room completion failure")
            }
            return delegate.completeVectorIndexPublication(request)
        }
    }

    private companion object {
        const val GENERATION = 1L
        const val GROUP_ID = "group-1"
        const val RECEIPT_ID = "receipt-1"
        const val CHAT_RECALL_CORPUS_KEY = "chat_recall_long_term"
        const val FIXED_TIME = 1_784_000_000L
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(FIXED_TIME), ZoneOffset.UTC)
        val UNIT_VECTOR = floatArrayOf(1f, 0f, 0f, 0f)
        val TEST_DESCRIPTOR = MemoryEmbeddingDescriptor(
            providerId = "test-provider",
            runtimeVersion = "test-runtime-v1",
            modelId = "test-model",
            modelVersion = "test-model-v1",
            modelSha256 = "c".repeat(64),
            dimension = UNIT_VECTOR.size,
            normalized = true,
            tokenizerVersion = "test-tokenizer-v1",
            tokenizerFingerprint = "d".repeat(64),
            maxInputTokens = 256,
            pooling = MemoryEmbeddingPooling.CLS,
            queryPrefix = "query: ",
            documentPrefix = "document: "
        )
    }
}
