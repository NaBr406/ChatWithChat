package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.entity.MemoryCorpusState
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorManifest
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVectorIndexBootstrapServiceTest {

    @Test
    fun `fresh header only memory bootstraps without changing canonical bytes`() = runBlocking {
        val fixture = Fixture(replaceInitialContent = false)
        val contentBefore = fixture.fileStore.readLongTermMemory().getOrThrow()
        val hashBefore = fixture.sourceHash()

        val result = fixture.service().bootstrap()

        assertTrue(result is MemoryVectorIndexBootstrapResult.Scheduled)
        assertEquals(contentBefore, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(hashBefore, fixture.sourceHash())
        assertEquals(fixture.projectionHash(), fixture.corpus().recallProjectionHash)
        assertEquals(hashBefore, fixture.receipt(fixture.corpus()).targetSourceHash)
    }

    @Test
    fun `existing memory without corpus identity creates content unchanged local receipt`() = runBlocking {
        val fixture = Fixture()
        val contentBefore = fixture.fileStore.readLongTermMemory().getOrThrow()
        val hashBefore = fixture.sourceHash()
        val backupCountBefore = fixture.paths.backupDirectory.listFiles().orEmpty().size

        val result = fixture.service().bootstrap()

        assertTrue(result is MemoryVectorIndexBootstrapResult.Scheduled)
        result as MemoryVectorIndexBootstrapResult.Scheduled
        assertEquals(1L, result.generation)
        assertEquals(MemoryCorpusIndexStatus.PENDING, result.indexStatus)
        val corpus = fixture.corpus()
        val receipt = fixture.receipt(corpus)
        val group = checkNotNull(fixture.recoveryDao.getMutationGroup(receipt.groupId))
        assertEquals(fixture.projectionHash(), corpus.recallProjectionHash)
        assertEquals(FINGERPRINT_A, corpus.targetIndexFingerprint)
        assertEquals(hashBefore, receipt.baseSourceHash)
        assertEquals(hashBefore, receipt.targetSourceHash)
        assertEquals(FINGERPRINT_A, receipt.targetIndexFingerprint)
        assertEquals(MemoryMutationState.INDEX_PENDING, receipt.state)
        assertEquals(MemoryMutationState.INDEX_PENDING, group.state)
        assertNull(group.semanticJobId)
        assertNull(group.semanticBatchId)
        assertEquals(contentBefore, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(hashBefore, fixture.sourceHash())
        assertEquals(backupCountBefore, fixture.paths.backupDirectory.listFiles().orEmpty().size)
        assertEquals(1, fixture.jobDao.jobs.size)
        assertEquals(MemoryMaintenanceJobType.SYNC_VECTOR_INDEX, fixture.jobDao.jobs.single().type)
        val payload = Json.decodeFromString<MemoryIndexSyncJobPayload>(fixture.jobDao.jobs.single().payloadJson)
        assertEquals(group.groupId, payload.mutationGroupId)
        assertEquals(receipt.receiptId, payload.receiptId)
        assertEquals(corpus.generation, payload.generation)
        assertEquals(fixture.projectionHash(), payload.recallProjectionHash)
        assertEquals(FINGERPRINT_A, payload.targetIndexFingerprint)
        assertEquals(1, fixture.workEnqueuer.enqueueCalls)
    }

    @Test
    fun `same source hash and fingerprint rerun has no side effects`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.service().bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled
        val corpusBefore = fixture.corpus()
        val receiptBefore = fixture.receipt(corpusBefore)
        val workCountBefore = fixture.workEnqueuer.enqueueCalls

        val second = fixture.service().bootstrap()

        assertTrue(second is MemoryVectorIndexBootstrapResult.AlreadyCurrent)
        second as MemoryVectorIndexBootstrapResult.AlreadyCurrent
        assertEquals(first.generation, second.generation)
        assertEquals(corpusBefore, fixture.corpus())
        assertEquals(receiptBefore, fixture.receipt(fixture.corpus()))
        assertEquals(1, fixture.jobDao.jobs.size)
        assertEquals(workCountBefore, fixture.workEnqueuer.enqueueCalls)
    }

    @Test
    fun `observation only canonical update remains projection current on restart`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.service().bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled
        val exactHashBefore = fixture.sourceHash()
        val projectionHashBefore = fixture.projectionHash()
        val receiptBefore = fixture.receipt(fixture.corpus())
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(
                listOf(
                    INITIAL_ENTRY.copy(
                        lastObservedAt = 2L,
                        evidenceRefs = listOf("chat:1:user:1", "chat:2:user:1")
                    )
                )
            )
        ).getOrThrow()

        val second = fixture.service().bootstrap()

        assertTrue(second is MemoryVectorIndexBootstrapResult.AlreadyCurrent)
        second as MemoryVectorIndexBootstrapResult.AlreadyCurrent
        assertEquals(first.generation, second.generation)
        assertNotEquals(exactHashBefore, fixture.sourceHash())
        assertEquals(projectionHashBefore, fixture.projectionHash())
        assertEquals(projectionHashBefore, second.recallProjectionHash)
        assertEquals(receiptBefore.targetSourceHash, fixture.receipt(fixture.corpus()).targetSourceHash)
        assertEquals(1, fixture.jobDao.jobs.size)
        assertEquals(1, fixture.workEnqueuer.enqueueCalls)
    }

    @Test
    fun `content change advances generation and preserves the new canonical hash`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.service().bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled
        fixture.fileStore.replaceLongTermMemory(UPDATED_MARKDOWN).getOrThrow()

        val second = fixture.service().bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled

        assertTrue(second.generation > first.generation)
        assertEquals(fixture.projectionHash(), second.recallProjectionHash)
        assertEquals(UPDATED_MARKDOWN, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(fixture.projectionHash(), fixture.corpus().recallProjectionHash)
        assertEquals(2, fixture.jobDao.jobs.size)
    }

    @Test
    fun `fingerprint change rebuilds once without changing memory content`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.service(FINGERPRINT_A).bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled
        val contentBefore = fixture.fileStore.readLongTermMemory().getOrThrow()
        val hashBefore = fixture.sourceHash()

        val second = fixture.service(FINGERPRINT_B).bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled
        val third = fixture.service(FINGERPRINT_B).bootstrap()

        assertTrue(second.generation > first.generation)
        assertEquals(FINGERPRINT_B, second.indexFingerprint)
        assertEquals(contentBefore, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(hashBefore, fixture.sourceHash())
        assertEquals(FINGERPRINT_B, fixture.corpus().targetIndexFingerprint)
        assertTrue(third is MemoryVectorIndexBootstrapResult.AlreadyCurrent)
        assertEquals(2, fixture.jobDao.jobs.size)
    }

    @Test
    fun `returning to a historical identity still allocates a newer generation`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.service().bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled
        val firstReceiptId = fixture.corpus().latestReceiptId
        fixture.fileStore.replaceLongTermMemory(UPDATED_MARKDOWN).getOrThrow()
        val second = fixture.service().bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled
        fixture.fileStore.replaceLongTermMemory(INITIAL_MARKDOWN).getOrThrow()

        val third = fixture.service().bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled

        assertTrue(second.generation > first.generation)
        assertTrue(third.generation > second.generation)
        assertTrue(firstReceiptId != fixture.corpus().latestReceiptId)
        assertEquals(fixture.projectionHash(), fixture.corpus().recallProjectionHash)
    }

    @Test
    fun `prepared local receipt is recovered after process restart without another generation`() = runBlocking {
        val fixture = Fixture()
        val sourceContent = fixture.fileStore.readLongTermMemory().getOrThrow()
        val prepared = fixture.coordinator.prepareLocalIndexBootstrap(
            sourceContent = sourceContent,
            canonicalSourceHash = fixture.sourceHash(),
            recallProjectionHash = fixture.projectionHash(),
            targetIndexFingerprint = FINGERPRINT_A,
            observedCorpusGeneration = 0L
        )
        assertNull(fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))

        val result = fixture.restartedService().bootstrap()

        assertTrue(result is MemoryVectorIndexBootstrapResult.Scheduled)
        result as MemoryVectorIndexBootstrapResult.Scheduled
        assertEquals(prepared.group.generation, result.generation)
        assertEquals(prepared.group.groupId, fixture.receipt(fixture.corpus()).groupId)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.group(fixture.corpus()).state)
        assertEquals(1, fixture.jobDao.jobs.size)
    }

    @Test
    fun `matching corpus without receipt is repaired with a newer durable generation`() = runBlocking {
        val fixture = Fixture()
        val sourceHash = fixture.sourceHash()
        fixture.recoveryDao.insertCorpusStateIgnore(
            MemoryCorpusState(
                corpus = CHAT_RECALL_CORPUS_KEY,
                sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                recallProjectionHash = fixture.projectionHash(),
                generation = 7L,
                targetIndexFingerprint = FINGERPRINT_A,
                indexStatus = MemoryCorpusIndexStatus.WAITING_REPAIR,
                indexedGeneration = null,
                indexedRecallProjectionHash = null,
                indexedFingerprint = null,
                latestReceiptId = null,
                lastError = "room_corpus_is_missing_vector_receipt_identity",
                createdAt = FIXED_TIME,
                updatedAt = FIXED_TIME
            )
        )

        val result = fixture.service().bootstrap() as MemoryVectorIndexBootstrapResult.Scheduled

        assertEquals(8L, result.generation)
        assertEquals(MemoryCorpusIndexStatus.PENDING, fixture.corpus().indexStatus)
        assertEquals(sourceHash, fixture.receipt(fixture.corpus()).targetSourceHash)
    }

    @Test
    fun `not provisioned embedding leaves bootstrap durable but never ready`() = runBlocking {
        val fixture = Fixture()
        fixture.service().bootstrap()
        val recovery = MemoryVectorIndexRecoveryService(
            recoveryDao = fixture.recoveryDao,
            snapshotSource = MemoryCorpusSnapshotter(fixture.fileStore, MemoryChunker()),
            memoryFileStore = fixture.fileStore,
            vectorStore = MissingMemoryVectorStore(),
            embeddingCapabilitySource = MemoryEmbeddingCapabilitySource {
                MemoryEmbeddingCapability.Unavailable(
                    MemoryEmbeddingAvailability.Unavailable(
                        MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED,
                        "production artifacts are absent"
                    )
                )
            },
            maintenanceScheduler = MemoryMaintenanceScheduler(fixture.jobDao, FIXED_CLOCK),
            clock = FIXED_CLOCK
        ).reconcile()

        assertEquals(1, recovery.blockedCount)
        assertEquals(MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY, fixture.corpus().indexStatus)
        assertFalse(fixture.corpus().indexStatus == MemoryCorpusIndexStatus.READY)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, fixture.jobDao.jobs.single().status)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.receipt(fixture.corpus()).state)
    }

    private class Fixture(
        replaceInitialContent: Boolean = true
    ) {
        val root = Files.createTempDirectory("memory-vector-bootstrap").toFile()
        val paths = MemoryFilePaths(root)
        val fileStore = MemoryFileStore(paths, FIXED_CLOCK)
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val workEnqueuer = RecordingWorkEnqueuer()
        val coordinator = coordinator(fileStore)

        init {
            fileStore.ensureStore().getOrThrow()
            if (replaceInitialContent) {
                fileStore.replaceLongTermMemory(INITIAL_MARKDOWN).getOrThrow()
            }
        }

        fun service(fingerprint: String = FINGERPRINT_A) = MemoryVectorIndexBootstrapService(
            recoveryDao = recoveryDao,
            memoryFileStore = fileStore,
            mutationCoordinator = coordinator,
            targetIndexFingerprint = fingerprint
        )

        fun restartedService() = MemoryVectorIndexBootstrapService(
            recoveryDao = recoveryDao,
            memoryFileStore = MemoryFileStore(paths, FIXED_CLOCK),
            mutationCoordinator = coordinator(MemoryFileStore(paths, FIXED_CLOCK)),
            targetIndexFingerprint = FINGERPRINT_A
        )

        fun sourceHash(): String = fileStore.currentMemoryFileHash(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        ).getOrThrow()

        suspend fun projectionHash(): String = MemoryChunker().chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            fileStore.readLongTermMemory().getOrThrow(),
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        ).projectionHash

        suspend fun corpus() = checkNotNull(recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))

        suspend fun receipt(corpus: MemoryCorpusState) =
            checkNotNull(recoveryDao.getMutationReceipt(checkNotNull(corpus.latestReceiptId)))

        suspend fun group(corpus: MemoryCorpusState) =
            checkNotNull(recoveryDao.getMutationGroup(receipt(corpus).groupId))

        private fun coordinator(store: MemoryFileStore) = MemoryMutationCoordinator(
            recoveryDao = recoveryDao,
            memoryFileStore = store,
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
            workEnqueuer = workEnqueuer,
            clock = FIXED_CLOCK
        )
    }

    private class MissingMemoryVectorStore : MemoryVectorStore {
        override fun readManifest(): MemoryVectorManifest? = null

        override fun countChunks(): Long = 0

        override fun verifySnapshot(
            expectation: MemoryVectorSnapshotExpectation
        ): MemoryVectorSnapshotVerification = MemoryVectorSnapshotVerification.Missing

        override fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult =
            error("A not-provisioned bootstrap must not publish vectors")

        override fun query(request: MemoryVectorQuery): MemoryVectorQueryResult =
            error("Vector query is not used by bootstrap recovery")

        override fun clearSnapshot() = Unit

        override fun deleteDerivedStore() = Unit

        override fun recoverFromCorruption(cause: Throwable): Boolean = false

        override fun close() = Unit
    }

    private companion object {
        const val CHAT_RECALL_CORPUS_KEY = "chat_recall_long_term"
        const val FIXED_TIME = 1_784_000_000L
        val INITIAL_ENTRY = MarkdownMemoryEntry(
            id = "mem_bootstrap_preference",
            text = "Existing user preference",
            type = "communication_style",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 1L,
            updatedAt = 1L,
            lastObservedAt = 1L,
            canonicalKey = "communication.response_style",
            scope = "general",
            recallState = MemoryRecallState.QUERY,
            evidenceRefs = listOf("chat:1:user:1")
        )
        val INITIAL_MARKDOWN = MarkdownMemoryCodec().renderLongTerm(listOf(INITIAL_ENTRY))
        val UPDATED_MARKDOWN = MarkdownMemoryCodec().renderLongTerm(
            listOf(INITIAL_ENTRY.copy(text = "Updated user preference", updatedAt = 2L, lastObservedAt = 2L))
        )
        val FINGERPRINT_A = "a".repeat(64)
        val FINGERPRINT_B = "b".repeat(64)
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(FIXED_TIME), ZoneOffset.UTC)
    }
}
