package dev.chungjungsoo.gptmobile.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryCorpusAdvanceOutcome
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryCorpusAdvanceRequest
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMutationPrepareRequest
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMutationReceiptDraft
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryVectorIndexPublicationOutcome
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryVectorIndexPublicationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class MemoryRecoveryDaoInstrumentedTest {

    private lateinit var database: ChatDatabaseV2
    private lateinit var dao: MemoryRecoveryDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ChatDatabaseV2::class.java
        ).build()
        dao = database.memoryRecoveryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun concurrentPrepare_allocatesUniqueMonotonicGenerations() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val prepared = (1..12).map { index ->
            async(Dispatchers.IO) {
                start.await()
                dao.prepareMutation(prepareRequest(index))
            }
        }

        start.complete(Unit)
        val results = prepared.awaitAll()

        assertEquals((1L..12L).toList(), results.map { it.group.generation }.sorted())
        assertTrue(results.all { it.isNew })
        assertTrue(results.all { result ->
            result.receipts.size == 1 && result.receipts.single().generation == result.group.generation
        })
        assertEquals(12L, dao.getLatestMutationGeneration())
    }

    @Test
    fun prepareReplay_isIdempotentAndSemanticLookupReturnsEarliestGroup() = runBlocking {
        val firstRequest = prepareRequest(index = 1, semanticJobId = "shared-semantic")
        val first = dao.prepareMutation(firstRequest)
        val replay = dao.prepareMutation(firstRequest)
        val second = dao.prepareMutation(prepareRequest(index = 2, semanticJobId = "shared-semantic"))

        assertTrue(first.isNew)
        assertFalse(replay.isNew)
        assertEquals(first.group, replay.group)
        assertEquals(first.receipts, replay.receipts)
        assertTrue(first.group.idempotencyKey.endsWith("|generation=1"))
        assertTrue(first.receipts.single().idempotencyKey.contains("|generation=1|receipt="))
        assertEquals(first.group.groupId, dao.getMutationGroupBySemanticJobId("shared-semantic")?.groupId)
        assertEquals(2L, second.group.generation)

        val mismatchedReplay = runCatching {
            dao.prepareMutation(
                firstRequest.copy(
                    receipts = firstRequest.receipts.map { receipt ->
                        receipt.copy(targetSourceHash = "different-target")
                    }
                )
            )
        }
        assertTrue(mismatchedReplay.isFailure)
        assertEquals("target-1", dao.getMutationReceipts(first.group.groupId).single().targetSourceHash)
    }

    @Test
    fun receiptInsertFailure_rollsBackNewGroup() = runBlocking {
        dao.prepareMutation(prepareRequest(index = 1, receiptId = "shared-receipt"))

        val failed = runCatching {
            dao.prepareMutation(prepareRequest(index = 2, receiptId = "shared-receipt"))
        }

        assertTrue(failed.isFailure)
        assertNull(dao.getMutationGroup("group-2"))
        assertEquals(1L, dao.getLatestMutationGeneration())
    }

    @Test
    fun groupCompletion_requiresCompleteReceiptSetAndMatchingVersion() = runBlocking {
        val request = prepareRequest(index = 1).copy(
            receipts = listOf(
                receiptDraft(index = 1, receiptId = "receipt-a", sourcePath = "MEMORY.md"),
                receiptDraft(index = 1, receiptId = "receipt-b", sourcePath = "memory/2026-07-13.md")
            )
        )
        val prepared = dao.prepareMutation(request)
        val firstReceipt = prepared.receipts.first()
        val secondReceipt = prepared.receipts.last()

        assertEquals(1, commitReceipt(firstReceipt.receiptId))
        assertEquals(
            0,
            dao.completeMutationGroupIfReceiptsCommitted(
                groupId = prepared.group.groupId,
                expectedGeneration = prepared.group.generation,
                expectedState = STATE_PREPARED,
                expectedRowVersion = prepared.group.rowVersion,
                committedReceiptStates = COMMITTED_RECEIPT_STATES,
                newState = STATE_FILE_COMMITTED,
                completedAt = 20
            )
        )

        assertEquals(1, commitReceipt(secondReceipt.receiptId))
        assertEquals(
            1,
            dao.completeMutationGroupIfReceiptsCommitted(
                groupId = prepared.group.groupId,
                expectedGeneration = prepared.group.generation,
                expectedState = STATE_PREPARED,
                expectedRowVersion = prepared.group.rowVersion,
                committedReceiptStates = COMMITTED_RECEIPT_STATES,
                newState = STATE_FILE_COMMITTED,
                completedAt = 21
            )
        )
        assertEquals(
            0,
            dao.completeMutationGroupIfReceiptsCommitted(
                groupId = prepared.group.groupId,
                expectedGeneration = prepared.group.generation,
                expectedState = STATE_PREPARED,
                expectedRowVersion = prepared.group.rowVersion,
                committedReceiptStates = COMMITTED_RECEIPT_STATES,
                newState = STATE_FILE_COMMITTED,
                completedAt = 22
            )
        )

        val completed = checkNotNull(dao.getMutationGroup(prepared.group.groupId))
        assertEquals(STATE_FILE_COMMITTED, completed.state)
        assertEquals(1L, completed.rowVersion)
        assertEquals(21L, completed.completedAt)
    }

    @Test
    fun emptyMutation_persistsAndCompletesWithoutReceipts() = runBlocking {
        val prepared = dao.prepareMutation(prepareRequest(index = 1).copy(receipts = emptyList()))

        assertTrue(prepared.isNew)
        assertTrue(prepared.receipts.isEmpty())
        assertEquals(0, prepared.group.expectedReceiptCount)
        assertEquals(
            1,
            dao.completeEmptyMutationGroupCas(
                groupId = prepared.group.groupId,
                expectedGeneration = prepared.group.generation,
                expectedState = STATE_PREPARED,
                expectedRowVersion = prepared.group.rowVersion,
                newState = STATE_FILE_COMMITTED,
                completedAt = 20
            )
        )
        assertEquals(STATE_FILE_COMMITTED, dao.getMutationGroup(prepared.group.groupId)?.state)
    }

    @Test
    fun corpusGeneration_processesAtoBtoAAndRejectsStaleReplay() = runBlocking {
        val first = dao.prepareMutation(prepareRequest(1))
        val second = dao.prepareMutation(prepareRequest(2))
        val third = dao.prepareMutation(prepareRequest(3))

        val revisionA1 = corpusRequest(first.group.generation, "hash-a", first.receipts.single().receiptId, 10)
        val revisionB = corpusRequest(second.group.generation, "hash-b", second.receipts.single().receiptId, 20)
        val revisionA2 = corpusRequest(third.group.generation, "hash-a", third.receipts.single().receiptId, 30)

        assertEquals(MemoryCorpusAdvanceOutcome.ADVANCED, dao.advanceCorpusGeneration(revisionA1).outcome)
        assertEquals(MemoryCorpusAdvanceOutcome.ADVANCED, dao.advanceCorpusGeneration(revisionB).outcome)
        assertEquals(MemoryCorpusAdvanceOutcome.ADVANCED, dao.advanceCorpusGeneration(revisionA2).outcome)
        assertEquals(MemoryCorpusAdvanceOutcome.ALREADY_CURRENT, dao.advanceCorpusGeneration(revisionA2).outcome)
        assertEquals(MemoryCorpusAdvanceOutcome.STALE, dao.advanceCorpusGeneration(revisionB).outcome)

        val current = checkNotNull(dao.getCorpusState(CORPUS_LONG_TERM))
        assertEquals(3L, current.generation)
        assertEquals("hash-a", current.sourceHash)
        assertEquals(third.receipts.single().receiptId, current.latestReceiptId)
        assertEquals(2L, current.rowVersion)
    }

    @Test
    fun receiptAndCorpusCas_rejectStaleVersionAndGeneration() = runBlocking {
        val first = dao.prepareMutation(prepareRequest(1))
        val second = dao.prepareMutation(prepareRequest(2))
        val firstReceipt = first.receipts.single()

        assertEquals(1, commitReceipt(firstReceipt.receiptId))
        assertEquals(
            0,
            dao.transitionMutationReceiptCas(
                receiptId = firstReceipt.receiptId,
                groupId = firstReceipt.groupId,
                expectedGeneration = firstReceipt.generation,
                expectedState = STATE_PREPARED,
                expectedRowVersion = firstReceipt.rowVersion,
                expectedTargetSourceHash = firstReceipt.targetSourceHash,
                expectedTargetIndexFingerprint = firstReceipt.targetIndexFingerprint,
                newState = STATE_INDEXED,
                attemptIncrement = 0,
                lastError = null,
                updatedAt = 30,
                fileCommittedAt = null,
                indexedAt = 30
            )
        )

        val firstCorpus = dao.advanceCorpusGeneration(
            corpusRequest(first.group.generation, "hash-a", firstReceipt.receiptId, 10)
        ).state
        val secondCorpus = dao.advanceCorpusGeneration(
            corpusRequest(second.group.generation, "hash-b", second.receipts.single().receiptId, 20)
        ).state

        assertEquals(
            0,
            dao.markCorpusIndexedCas(
                corpus = CORPUS_LONG_TERM,
                expectedGeneration = firstCorpus.generation,
                expectedSourceHash = firstCorpus.sourceHash,
                expectedTargetIndexFingerprint = FINGERPRINT,
                expectedRowVersion = firstCorpus.rowVersion,
                indexedStatus = STATE_INDEXED,
                updatedAt = 30
            )
        )
        assertEquals(
            1,
            dao.markCorpusIndexedCas(
                corpus = CORPUS_LONG_TERM,
                expectedGeneration = secondCorpus.generation,
                expectedSourceHash = secondCorpus.sourceHash,
                expectedTargetIndexFingerprint = FINGERPRINT,
                expectedRowVersion = secondCorpus.rowVersion,
                indexedStatus = STATE_INDEXED,
                updatedAt = 31
            )
        )

        val indexed = checkNotNull(dao.getCorpusState(CORPUS_LONG_TERM))
        assertEquals(second.group.generation, indexed.indexedGeneration)
        assertEquals("hash-b", indexed.indexedSourceHash)
        assertEquals(FINGERPRINT, indexed.indexedFingerprint)
        assertEquals(STATE_INDEXED, indexed.indexStatus)
    }

    @Test
    fun completeVectorIndexPublication_atomicallyMarksCorpusReceiptAndGroupComplete() = runBlocking {
        val request = preparePendingPublication(index = 1)

        assertEquals(
            MemoryVectorIndexPublicationOutcome.COMPLETED,
            dao.completeVectorIndexPublication(request)
        )

        val corpus = checkNotNull(dao.getCorpusState(request.corpus))
        assertEquals(STATE_CORPUS_READY, corpus.indexStatus)
        assertEquals(request.generation, corpus.indexedGeneration)
        assertEquals(request.sourceHash, corpus.indexedSourceHash)
        assertEquals(request.targetIndexFingerprint, corpus.indexedFingerprint)

        val receipt = checkNotNull(dao.getMutationReceipt(request.receiptId))
        assertEquals(STATE_INDEXED, receipt.state)
        assertEquals(request.completedAt, receipt.indexedAt)

        val group = checkNotNull(dao.getMutationGroup(request.mutationGroupId))
        assertEquals(STATE_INDEXED, group.state)
        assertEquals(request.completedAt, group.completedAt)
    }

    @Test
    fun completeVectorIndexPublication_replayIsIdempotent() = runBlocking {
        val request = preparePendingPublication(index = 1)
        assertEquals(
            MemoryVectorIndexPublicationOutcome.COMPLETED,
            dao.completeVectorIndexPublication(request)
        )
        val completedCorpus = checkNotNull(dao.getCorpusState(request.corpus))
        val completedReceipt = checkNotNull(dao.getMutationReceipt(request.receiptId))
        val completedGroup = checkNotNull(dao.getMutationGroup(request.mutationGroupId))

        assertEquals(
            MemoryVectorIndexPublicationOutcome.ALREADY_COMPLETE,
            dao.completeVectorIndexPublication(request.copy(completedAt = request.completedAt + 10))
        )
        assertEquals(completedCorpus, dao.getCorpusState(request.corpus))
        assertEquals(completedReceipt, dao.getMutationReceipt(request.receiptId))
        assertEquals(completedGroup, dao.getMutationGroup(request.mutationGroupId))
    }

    @Test
    fun completeVectorIndexPublication_keepsSemanticAcknowledgementPendingGroup() = runBlocking {
        val request = preparePendingPublication(
            index = 1,
            groupState = STATE_SEMANTIC_ACK_PENDING
        )
        val pendingGroup = checkNotNull(dao.getMutationGroup(request.mutationGroupId))

        assertEquals(
            MemoryVectorIndexPublicationOutcome.COMPLETED,
            dao.completeVectorIndexPublication(request)
        )

        assertEquals(STATE_CORPUS_READY, dao.getCorpusState(request.corpus)?.indexStatus)
        assertEquals(STATE_INDEXED, dao.getMutationReceipt(request.receiptId)?.state)
        assertEquals(pendingGroup, dao.getMutationGroup(request.mutationGroupId))
    }

    @Test
    fun completeVectorIndexPublication_olderGenerationCannotChangeNewerState() = runBlocking {
        val olderRequest = preparePendingPublication(index = 1)
        val newerRequest = preparePendingPublication(index = 2)
        val newerCorpus = checkNotNull(dao.getCorpusState(newerRequest.corpus))
        val newerReceipt = checkNotNull(dao.getMutationReceipt(newerRequest.receiptId))
        val newerGroup = checkNotNull(dao.getMutationGroup(newerRequest.mutationGroupId))
        val olderReceipt = checkNotNull(dao.getMutationReceipt(olderRequest.receiptId))
        val olderGroup = checkNotNull(dao.getMutationGroup(olderRequest.mutationGroupId))

        assertEquals(
            MemoryVectorIndexPublicationOutcome.SUPERSEDED,
            dao.completeVectorIndexPublication(olderRequest)
        )
        assertEquals(newerCorpus, dao.getCorpusState(newerRequest.corpus))
        assertEquals(newerReceipt, dao.getMutationReceipt(newerRequest.receiptId))
        assertEquals(newerGroup, dao.getMutationGroup(newerRequest.mutationGroupId))
        assertEquals(olderReceipt, dao.getMutationReceipt(olderRequest.receiptId))
        assertEquals(olderGroup, dao.getMutationGroup(olderRequest.mutationGroupId))
    }

    @Test
    fun completeVectorIndexPublication_sameGenerationIdentityConflictChangesNothing() = runBlocking {
        val request = preparePendingPublication(index = 1)
        val pendingCorpus = checkNotNull(dao.getCorpusState(request.corpus))
        val pendingReceipt = checkNotNull(dao.getMutationReceipt(request.receiptId))
        val pendingGroup = checkNotNull(dao.getMutationGroup(request.mutationGroupId))

        assertEquals(
            MemoryVectorIndexPublicationOutcome.CONFLICT,
            dao.completeVectorIndexPublication(request.copy(sourceHash = "conflicting-hash"))
        )
        assertEquals(pendingCorpus, dao.getCorpusState(request.corpus))
        assertEquals(pendingReceipt, dao.getMutationReceipt(request.receiptId))
        assertEquals(pendingGroup, dao.getMutationGroup(request.mutationGroupId))
    }

    private suspend fun preparePendingPublication(
        index: Int,
        groupState: String = STATE_INDEX_PENDING
    ): MemoryVectorIndexPublicationRequest {
        val prepared = dao.prepareMutation(prepareRequest(index))
        val receipt = prepared.receipts.single()
        assertEquals(
            1,
            dao.transitionMutationReceiptCas(
                receiptId = receipt.receiptId,
                groupId = receipt.groupId,
                expectedGeneration = receipt.generation,
                expectedState = receipt.state,
                expectedRowVersion = receipt.rowVersion,
                expectedTargetSourceHash = receipt.targetSourceHash,
                expectedTargetIndexFingerprint = receipt.targetIndexFingerprint,
                newState = STATE_INDEX_PENDING,
                attemptIncrement = 0,
                lastError = null,
                updatedAt = index.toLong() + 10,
                fileCommittedAt = index.toLong() + 10,
                indexedAt = null
            )
        )
        assertEquals(
            1,
            dao.transitionMutationGroupCas(
                groupId = prepared.group.groupId,
                expectedGeneration = prepared.group.generation,
                expectedState = prepared.group.state,
                expectedRowVersion = prepared.group.rowVersion,
                newState = groupState,
                lastError = null,
                updatedAt = index.toLong() + 10,
                completedAt = null
            )
        )
        assertEquals(
            MemoryCorpusAdvanceOutcome.ADVANCED,
            dao.advanceCorpusGeneration(
                corpusRequest(
                    generation = prepared.group.generation,
                    sourceHash = receipt.targetSourceHash,
                    receiptId = receipt.receiptId,
                    updatedAt = index.toLong() + 20
                ).copy(indexStatus = STATE_CORPUS_PENDING)
            ).outcome
        )
        return MemoryVectorIndexPublicationRequest(
            corpus = CORPUS_LONG_TERM,
            mutationGroupId = prepared.group.groupId,
            receiptId = receipt.receiptId,
            generation = prepared.group.generation,
            sourcePath = receipt.sourcePath,
            sourceHash = receipt.targetSourceHash,
            targetIndexFingerprint = checkNotNull(receipt.targetIndexFingerprint),
            completedAt = index.toLong() + 30
        )
    }

    private suspend fun commitReceipt(receiptId: String): Int {
        val receipt = checkNotNull(dao.getMutationReceipt(receiptId))
        return dao.transitionMutationReceiptCas(
            receiptId = receipt.receiptId,
            groupId = receipt.groupId,
            expectedGeneration = receipt.generation,
            expectedState = receipt.state,
            expectedRowVersion = receipt.rowVersion,
            expectedTargetSourceHash = receipt.targetSourceHash,
            expectedTargetIndexFingerprint = receipt.targetIndexFingerprint,
            newState = STATE_FILE_COMMITTED,
            attemptIncrement = 1,
            lastError = null,
            updatedAt = 10,
            fileCommittedAt = 10,
            indexedAt = null
        )
    }

    private fun prepareRequest(
        index: Int,
        semanticJobId: String = "semantic-$index",
        receiptId: String = "receipt-$index"
    ): MemoryMutationPrepareRequest = MemoryMutationPrepareRequest(
        groupId = "group-$index",
        semanticJobId = semanticJobId,
        semanticBatchId = "batch-$index",
        state = STATE_PREPARED,
        idempotencyKeyBase = "group-key-$index",
        receipts = listOf(receiptDraft(index, receiptId, "MEMORY.md")),
        createdAt = index.toLong()
    )

    private fun receiptDraft(
        index: Int,
        receiptId: String,
        sourcePath: String
    ): MemoryMutationReceiptDraft = MemoryMutationReceiptDraft(
        receiptId = receiptId,
        sourcePath = sourcePath,
        baseSourceHash = "base-$index",
        targetSourceHash = "target-$index",
        stagedTargetPath = ".staging/group-$index/$receiptId.md",
        state = STATE_PREPARED,
        idempotencyKeyBase = "receipt-key-$index-$receiptId",
        targetIndexFingerprint = FINGERPRINT
    )

    private fun corpusRequest(
        generation: Long,
        sourceHash: String,
        receiptId: String,
        updatedAt: Long
    ): MemoryCorpusAdvanceRequest = MemoryCorpusAdvanceRequest(
        corpus = CORPUS_LONG_TERM,
        sourcePath = "MEMORY.md",
        sourceHash = sourceHash,
        generation = generation,
        targetIndexFingerprint = FINGERPRINT,
        indexStatus = STATE_INDEX_PENDING,
        latestReceiptId = receiptId,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    companion object {
        private const val CORPUS_LONG_TERM = "CHAT_RECALL_LONG_TERM"
        private const val FINGERPRINT = "index-fingerprint"
        private const val STATE_PREPARED = "prepared"
        private const val STATE_FILE_COMMITTED = "file_committed"
        private const val STATE_SEMANTIC_ACK_PENDING = "semantic_ack_pending"
        private const val STATE_INDEX_PENDING = "index_pending"
        private const val STATE_INDEXED = "indexed"
        private const val STATE_CORPUS_PENDING = "pending"
        private const val STATE_CORPUS_READY = "ready"
        private val COMMITTED_RECEIPT_STATES = listOf(
            STATE_FILE_COMMITTED,
            STATE_INDEX_PENDING,
            STATE_INDEXED
        )
    }
}
