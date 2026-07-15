package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.dao.MemoryCorpusAdvanceRequest
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
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorStoreCorruptionException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVectorIndexRecoveryServiceTest {

    @Test
    fun `matching manifest fast forwards blocked Room state without embedding capability`() = runBlocking {
        val fixture = fixture(
            corpusStatus = MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY,
            jobStatus = MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY
        )
        fixture.vectorStore.install(fixture.matchingManifest(), fixture.snapshot.chunks.size.toLong())

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(completedCount = 1), result)
        val corpus = fixture.corpus()
        assertEquals(MemoryCorpusIndexStatus.READY, corpus.indexStatus)
        assertEquals(GENERATION, corpus.indexedGeneration)
        assertEquals(fixture.sourceHash, corpus.indexedSourceHash)
        assertEquals(fixture.configuration.fingerprint(), corpus.indexedFingerprint)
        assertEquals(MemoryMutationState.INDEXED, fixture.receipt().state)
        assertEquals(MemoryMutationState.INDEXED, fixture.group().state)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, fixture.onlyJob().status)
        assertEquals(0, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.vectorStore.deleteDerivedStoreCalls)
        assertFalse(fixture.stagedTarget.toFile().exists())
    }

    @Test
    fun `ready Room state with missing manifest becomes blocked without a job loop`() = runBlocking {
        val fixture = fixture(
            corpusStatus = MemoryCorpusIndexStatus.READY,
            jobStatus = MemoryMaintenanceJobStatus.SUCCEEDED
        )

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(blockedCount = 1), result)
        assertEquals(MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY, fixture.corpus().indexStatus)
        assertEquals("embedding_unavailable:artifact_missing", fixture.corpus().lastError)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, fixture.onlyJob().status)
        assertEquals("embedding_unavailable:artifact_missing", fixture.onlyJob().blockedReason)
        assertNull(fixture.onlyJob().nextRunAt)
        assertEquals(1, fixture.jobDao.jobs.size)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.receipt().state)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.group().state)
    }

    @Test
    fun `blocked corpus revives pending when embedding capability becomes ready`() = runBlocking {
        val fixture = fixture(
            corpusStatus = MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY,
            jobStatus = MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY
        )

        val result = fixture.service(fixture.readyCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(scheduledCount = 1), result)
        assertEquals(MemoryCorpusIndexStatus.PENDING, fixture.corpus().indexStatus)
        assertNull(fixture.corpus().lastError)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, fixture.onlyJob().status)
        assertEquals(FIXED_TIME, fixture.onlyJob().nextRunAt)
        assertEquals(1, fixture.onlyJob().retryCycle)
        assertEquals(1, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.provider.embedDocumentCalls)
        assertEquals(1, fixture.jobDao.jobs.size)
    }

    @Test
    fun `same generation conflicting manifest is preserved and fails closed`() = runBlocking {
        val fixture = fixture(jobStatus = MemoryMaintenanceJobStatus.SUCCEEDED)
        val conflictingManifest = fixture.matchingManifest().copy(
            identity = fixture.matchingManifest().identity.copy(sourceHash = "f".repeat(64))
        )
        fixture.vectorStore.install(conflictingManifest, fixture.snapshot.chunks.size.toLong())

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(conflictCount = 1), result)
        assertEquals(conflictingManifest, fixture.vectorStore.currentManifest)
        assertEquals(0, fixture.vectorStore.deleteDerivedStoreCalls)
        assertEquals(MemoryCorpusIndexStatus.WAITING_REPAIR, fixture.corpus().indexStatus)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, fixture.onlyJob().status)
    }

    @Test
    fun `older manifest is preserved while current generation becomes dependency blocked`() = runBlocking {
        val fixture = fixture()
        val olderManifest = fixture.matchingManifest().copy(
            identity = fixture.matchingManifest().identity.copy(corpusGeneration = GENERATION - 1)
        )
        fixture.vectorStore.install(olderManifest, fixture.snapshot.chunks.size.toLong())
        val markdownBefore = fixture.fileStore.readLongTermMemory().getOrThrow()

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(blockedCount = 1), result)
        assertEquals(olderManifest, fixture.vectorStore.currentManifest)
        assertEquals(0, fixture.vectorStore.deleteDerivedStoreCalls)
        assertEquals(markdownBefore, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY, fixture.corpus().indexStatus)
    }

    @Test
    fun `newer manifest is preserved when Room has not advanced`() = runBlocking {
        val fixture = fixture()
        val newerManifest = fixture.matchingManifest().copy(
            identity = fixture.matchingManifest().identity.copy(corpusGeneration = GENERATION + 1)
        )
        fixture.vectorStore.install(newerManifest, fixture.snapshot.chunks.size.toLong())

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(conflictCount = 1), result)
        assertEquals(newerManifest, fixture.vectorStore.currentManifest)
        assertEquals(0, fixture.vectorStore.deleteDerivedStoreCalls)
        assertEquals(MemoryCorpusIndexStatus.WAITING_REPAIR, fixture.corpus().indexStatus)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, fixture.onlyJob().status)
    }

    @Test
    fun `vector corruption deletes derived state and blocks when capability is unavailable`() = runBlocking {
        val fixture = fixture()
        fixture.vectorStore.install(fixture.matchingManifest(), fixture.snapshot.chunks.size.toLong())
        fixture.vectorStore.verifyFailure = MemoryVectorStoreCorruptionException("broken manifest")
        val markdownBefore = fixture.fileStore.readLongTermMemory().getOrThrow()

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(blockedCount = 1, deletedDerivedStoreCount = 1), result)
        assertEquals(1, fixture.vectorStore.recoverFromCorruptionCalls)
        assertNull(fixture.vectorStore.currentManifest)
        assertEquals(MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY, fixture.corpus().indexStatus)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, fixture.onlyJob().status)
        assertEquals(markdownBefore, fixture.fileStore.readLongTermMemory().getOrThrow())
    }

    @Test
    fun `vector corruption deletes derived state and schedules when capability is available`() = runBlocking {
        val fixture = fixture()
        fixture.vectorStore.install(fixture.matchingManifest(), fixture.snapshot.chunks.size.toLong())
        fixture.vectorStore.verifyFailure = MemoryVectorStoreCorruptionException("broken manifest")

        val result = fixture.service(fixture.readyCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(scheduledCount = 1, deletedDerivedStoreCount = 1), result)
        assertEquals(1, fixture.vectorStore.recoverFromCorruptionCalls)
        assertEquals(MemoryCorpusIndexStatus.PENDING, fixture.corpus().indexStatus)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, fixture.onlyJob().status)
        assertEquals(1, fixture.provider.availabilityCalls)
        assertEquals(0, fixture.provider.embedDocumentCalls)
    }

    @Test
    fun `canonical Markdown hash mismatch waits for repair without fast forward`() = runBlocking {
        val fixture = fixture(snapshotHash = "e".repeat(64))
        fixture.vectorStore.install(fixture.matchingManifest(), fixture.snapshot.chunks.size.toLong())
        val markdownBefore = fixture.fileStore.readLongTermMemory().getOrThrow()

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(conflictCount = 1), result)
        assertEquals(MemoryCorpusIndexStatus.WAITING_REPAIR, fixture.corpus().indexStatus)
        assertEquals("canonical_memory_does_not_match_room_corpus", fixture.corpus().lastError)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.receipt().state)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.group().state)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, fixture.onlyJob().status)
        assertEquals(0, fixture.vectorStore.verifySnapshotCalls)
        assertEquals(markdownBefore, fixture.fileStore.readLongTermMemory().getOrThrow())
    }

    @Test
    fun `matching manifest with wrong chunk count does not fast forward`() = runBlocking {
        val fixture = fixture()
        fixture.vectorStore.install(
            manifest = fixture.matchingManifest(),
            chunkCount = fixture.snapshot.chunks.size.toLong() - 1
        )

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(blockedCount = 1, deletedDerivedStoreCount = 1), result)
        assertEquals(1, fixture.vectorStore.recoverFromCorruptionCalls)
        assertEquals(MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY, fixture.corpus().indexStatus)
        assertNull(fixture.corpus().indexedGeneration)
        assertNull(fixture.corpus().indexedSourceHash)
        assertNull(fixture.corpus().indexedFingerprint)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.receipt().state)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.group().state)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, fixture.onlyJob().status)
    }

    @Test
    fun `matching identity with wrong chunk content does not fast forward`() = runBlocking {
        val fixture = fixture()
        fixture.vectorStore.install(
            manifest = fixture.matchingManifest(),
            chunkCount = fixture.snapshot.chunks.size.toLong(),
            contentMatchesExpectation = false
        )

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(blockedCount = 1, deletedDerivedStoreCount = 1), result)
        assertEquals(1, fixture.vectorStore.recoverFromCorruptionCalls)
        assertEquals(MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY, fixture.corpus().indexStatus)
        assertNull(fixture.corpus().indexedGeneration)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.receipt().state)
    }

    @Test
    fun `Markdown change before Room fast forward fails closed`() = runBlocking {
        val fixture = fixture()
        fixture.vectorStore.install(fixture.matchingManifest(), fixture.snapshot.chunks.size.toLong())
        fixture.snapshotSource.isCurrentResult = false

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(conflictCount = 1), result)
        assertEquals(MemoryCorpusIndexStatus.WAITING_REPAIR, fixture.corpus().indexStatus)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, fixture.onlyJob().status)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.receipt().state)
    }

    @Test
    fun `Room advance after verification prevents old generation downgrade`() = runBlocking {
        val fixture = fixture()
        fixture.vectorStore.onVerify = {
            runBlocking {
                fixture.recoveryDao.advanceCorpusGeneration(
                    MemoryCorpusAdvanceRequest(
                        corpus = CHAT_RECALL_CORPUS_KEY,
                        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                        sourceHash = "f".repeat(64),
                        generation = GENERATION + 1,
                        targetIndexFingerprint = "e".repeat(64),
                        indexStatus = MemoryCorpusIndexStatus.PENDING,
                        latestReceiptId = "newer-receipt",
                        createdAt = FIXED_TIME + 1,
                        updatedAt = FIXED_TIME + 1
                    )
                )
            }
        }

        val result = fixture.service(unavailableCapability()).reconcile()

        assertEquals(MemoryVectorIndexRecoveryResult(), result)
        assertEquals(GENERATION + 1, fixture.corpus().generation)
        assertEquals(MemoryCorpusIndexStatus.PENDING, fixture.corpus().indexStatus)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, fixture.onlyJob().status)
    }

    private suspend fun fixture(
        corpusStatus: String = MemoryCorpusIndexStatus.PENDING,
        jobStatus: String = MemoryMaintenanceJobStatus.PENDING,
        snapshotHash: String? = null
    ): Fixture {
        val root = Files.createTempDirectory("memory-vector-index-recovery")
        val fileStore = MemoryFileStore(MemoryFilePaths(root.toFile()), FIXED_CLOCK)
        fileStore.replaceLongTermMemory(CANONICAL_MARKDOWN).getOrThrow()
        val sourceHash = fileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrThrow()
        val configuration = configuration()
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            sourceHash = snapshotHash ?: sourceHash,
            generation = GENERATION,
            chunks = listOf(
                chunk("preference", 0, "Prefers concise answers"),
                chunk("project", 1, "Works on ChatWithChat")
            )
        )
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val payload = MemoryIndexSyncJobPayload(
            mutationGroupId = GROUP_ID,
            receiptId = RECEIPT_ID,
            generation = GENERATION,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            sourceHash = sourceHash,
            targetIndexFingerprint = configuration.fingerprint()
        )
        recoveryDao.insertMutationGroup(
            MemoryMutationGroup(
                groupId = GROUP_ID,
                generation = GENERATION,
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
                    receiptId = RECEIPT_ID,
                    groupId = GROUP_ID,
                    generation = GENERATION,
                    sourcePath = payload.sourcePath,
                    baseSourceHash = "b".repeat(64),
                    targetSourceHash = sourceHash,
                    stagedTargetPath = STAGED_TARGET_PATH,
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
                sourceHash = sourceHash,
                generation = GENERATION,
                targetIndexFingerprint = payload.targetIndexFingerprint,
                indexStatus = corpusStatus,
                indexedGeneration = GENERATION.takeIf { corpusStatus == MemoryCorpusIndexStatus.READY },
                indexedSourceHash = sourceHash.takeIf { corpusStatus == MemoryCorpusIndexStatus.READY },
                indexedFingerprint = payload.targetIndexFingerprint.takeIf { corpusStatus == MemoryCorpusIndexStatus.READY },
                latestReceiptId = RECEIPT_ID,
                lastError = null,
                createdAt = FIXED_TIME,
                updatedAt = FIXED_TIME
            )
        )
        val stagedTarget = root.resolve(STAGED_TARGET_PATH)
        Files.createDirectories(stagedTarget.parent)
        Files.write(stagedTarget, CANONICAL_MARKDOWN.toByteArray())
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                MemoryMaintenanceJob(
                    jobId = JOB_ID,
                    type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
                    status = jobStatus,
                    idempotencyKey = "memory-vector-sync:generation:$GENERATION",
                    payloadJson = Json.encodeToString(payload),
                    attempts = 0,
                    lastError = null,
                    createdAt = FIXED_TIME,
                    startedAt = null,
                    updatedAt = FIXED_TIME,
                    nextRunAt = null,
                    family = MemoryMaintenanceJobFamily.INDEX,
                    generation = GENERATION,
                    blockedReason = null
                )
            )
        )
        return Fixture(
            recoveryDao = recoveryDao,
            jobDao = jobDao,
            configuration = configuration,
            sourceHash = sourceHash,
            snapshot = snapshot,
            snapshotSource = FakeSnapshotSource(snapshot),
            provider = FakeEmbeddingProvider(),
            vectorStore = FakeMemoryVectorStore(),
            fileStore = fileStore,
            stagedTarget = stagedTarget
        )
    }

    private fun configuration() = MemoryVectorIndexConfiguration(
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
    ) = MemoryCorpusChunk(
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

    private fun unavailableCapability() = MemoryEmbeddingCapability.Unavailable(
        MemoryEmbeddingAvailability.Unavailable(
            MemoryEmbeddingAvailability.Reason.ARTIFACT_MISSING,
            "test model is absent"
        )
    )

    private data class Fixture(
        val recoveryDao: InMemoryMemoryRecoveryDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val configuration: MemoryVectorIndexConfiguration,
        val sourceHash: String,
        val snapshot: MemoryCorpusSnapshot,
        val snapshotSource: FakeSnapshotSource,
        val provider: FakeEmbeddingProvider,
        val vectorStore: FakeMemoryVectorStore,
        val fileStore: MemoryFileStore,
        val stagedTarget: java.nio.file.Path
    ) {
        fun service(capability: MemoryEmbeddingCapability) = MemoryVectorIndexRecoveryService(
            recoveryDao = recoveryDao,
            snapshotSource = snapshotSource,
            memoryFileStore = fileStore,
            vectorStore = vectorStore,
            embeddingCapabilitySource = MemoryEmbeddingCapabilitySource { capability },
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
            clock = FIXED_CLOCK
        )

        fun readyCapability() = MemoryEmbeddingCapability.Ready(provider, configuration)

        fun matchingManifest() = MemoryVectorManifest(
            identity = configuration.identity(
                sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                sourceHash = sourceHash,
                corpusGeneration = GENERATION
            ),
            expectedChunkCount = snapshot.chunks.size.toLong(),
            completedAt = FIXED_TIME,
            state = MemoryVectorManifestState.READY
        )

        suspend fun corpus() = checkNotNull(recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))

        suspend fun receipt() = checkNotNull(recoveryDao.getMutationReceipt(RECEIPT_ID))

        suspend fun group() = checkNotNull(recoveryDao.getMutationGroup(GROUP_ID))

        fun onlyJob() = jobDao.jobs.single()
    }

    private class FakeSnapshotSource(
        var snapshot: MemoryCorpusSnapshot,
        var isCurrentResult: Boolean = true
    ) : MemoryCorpusSnapshotSource {
        override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
            Result.success(listOf(snapshot))

        override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> =
            Result.success(isCurrentResult)
    }

    private class FakeEmbeddingProvider : MemoryEmbeddingProvider {
        override val descriptor: MemoryEmbeddingDescriptor = TEST_DESCRIPTOR
        var availabilityState: MemoryEmbeddingAvailability = MemoryEmbeddingAvailability.Available
        var availabilityCalls = 0
        var embedDocumentCalls = 0

        override suspend fun availability(): MemoryEmbeddingAvailability {
            availabilityCalls += 1
            return availabilityState
        }

        override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> {
            embedDocumentCalls += 1
            return Result.success(texts.map { UNIT_VECTOR.copyOf() })
        }

        override suspend fun embedQuery(text: String): Result<FloatArray> =
            Result.success(UNIT_VECTOR.copyOf())
    }

    private class FakeMemoryVectorStore : MemoryVectorStore {
        var currentManifest: MemoryVectorManifest? = null
            private set
        var currentChunkCount = 0L
            private set
        var contentMatchesExpectation = true
            private set
        var verifyFailure: Throwable? = null
        var verifySnapshotCalls = 0
        var deleteDerivedStoreCalls = 0
        var recoverFromCorruptionCalls = 0
        var onVerify: () -> Unit = {}

        override fun readManifest(): MemoryVectorManifest? = currentManifest

        override fun countChunks(): Long = currentChunkCount

        override fun verifySnapshot(
            expectation: MemoryVectorSnapshotExpectation
        ): MemoryVectorSnapshotVerification {
            verifySnapshotCalls += 1
            onVerify()
            verifyFailure?.let {
                verifyFailure = null
                recoverFromCorruptionCalls += 1
                clearSnapshot()
                return MemoryVectorSnapshotVerification.RecoveredCorruption
            }
            val manifest = currentManifest ?: return MemoryVectorSnapshotVerification.Missing
            val identity = manifest.identity
            val matches = identity.corpus == expectation.corpus &&
                identity.sourcePath == expectation.sourcePath &&
                identity.sourceHash == expectation.sourceHash &&
                identity.corpusGeneration == expectation.corpusGeneration &&
                identity.indexFingerprint == expectation.indexFingerprint
            if (!matches) return MemoryVectorSnapshotVerification.Stale(manifest)
            if (currentChunkCount != manifest.expectedChunkCount || !contentMatchesExpectation) {
                recoverFromCorruptionCalls += 1
                clearSnapshot()
                return MemoryVectorSnapshotVerification.RecoveredCorruption
            }
            return MemoryVectorSnapshotVerification.Ready(manifest)
        }

        override fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult {
            currentManifest = snapshot.manifest
            currentChunkCount = snapshot.chunks.size.toLong()
            return MemoryVectorPublishResult.PUBLISHED
        }

        override fun query(request: MemoryVectorQuery): MemoryVectorQueryResult =
            error("Vector query is not used by startup recovery")

        override fun clearSnapshot() {
            currentManifest = null
            currentChunkCount = 0
            contentMatchesExpectation = true
        }

        override fun deleteDerivedStore() {
            deleteDerivedStoreCalls += 1
            clearSnapshot()
        }

        override fun recoverFromCorruption(cause: Throwable): Boolean {
            recoverFromCorruptionCalls += 1
            verifyFailure = null
            clearSnapshot()
            return true
        }

        override fun close() = Unit

        fun install(
            manifest: MemoryVectorManifest,
            chunkCount: Long,
            contentMatchesExpectation: Boolean = true
        ) {
            currentManifest = manifest
            currentChunkCount = chunkCount
            this.contentMatchesExpectation = contentMatchesExpectation
        }
    }

    private companion object {
        const val GENERATION = 1L
        const val GROUP_ID = "group-1"
        const val RECEIPT_ID = "receipt-1"
        const val JOB_ID = "index-job-1"
        const val CHAT_RECALL_CORPUS_KEY = "chat_recall_long_term"
        const val STAGED_TARGET_PATH = ".staging/group-1_receipt-1.target"
        const val FIXED_TIME = 1_784_000_000L
        const val CANONICAL_MARKDOWN = "# MEMORY\n\nPrefers concise answers.\n"
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
