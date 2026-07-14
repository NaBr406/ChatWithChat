package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.InMemoryMemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingAvailability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingProvider
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexDefaults
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStore
import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMutationCoordinatorTest {

    @Test
    fun `concurrent local prepare keeps one durable staged receipt`() = runBlocking {
        val fixture = Fixture()
        val baseContent = fixture.fileStore.readLongTermMemory().getOrThrow()
        val targetContent = "# ChatWithChat Memory\n\n- Concurrent local migration"

        val prepared = coroutineScope {
            List(16) {
                async(Dispatchers.Default) {
                    fixture.coordinator.prepareLocalMutation(
                        operationKey = "concurrent-local-migration",
                        targets = listOf(fixture.longTermTarget(baseContent, targetContent))
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, prepared.map { it.group.groupId }.distinct().size)
        val receipt = prepared.first().receipts.single()
        assertTrue(fixture.root.resolve(receipt.stagedTargetPath).isFile)
        val restarted = fixture.newCoordinator(MemoryFileStore(fixture.paths, FIXED_CLOCK))
        assertTrue(restarted.reconcile(prepared.first()) is MemoryMutationCommitResult.CanonicalCommitted)
        assertEquals(targetContent + "\n", fixture.fileStore.readLongTermMemory().getOrThrow())
    }

    @Test
    fun `local mutation is non semantic idempotent and advances after content round trip`() = runBlocking {
        val fixture = Fixture()
        val contentA = fixture.fileStore.readLongTermMemory().getOrThrow()
        val contentB = "# ChatWithChat Memory\n\n- Local migration target"

        val firstPrepared = fixture.coordinator.prepareLocalMutation(
            operationKey = "legacy-personal-memory",
            targets = listOf(fixture.longTermTarget(contentA, contentB))
        )
        val replayedPrepare = fixture.coordinator.prepareLocalMutation(
            operationKey = "legacy-personal-memory",
            targets = listOf(fixture.longTermTarget(contentA, contentB))
        )

        assertEquals(firstPrepared.group.groupId, replayedPrepare.group.groupId)
        assertEquals(1L, firstPrepared.group.generation)
        assertEquals(null, firstPrepared.group.semanticJobId)
        assertEquals(null, firstPrepared.group.semanticBatchId)
        val firstResult = fixture.coordinator.reconcile(firstPrepared)
        assertTrue(firstResult is MemoryMutationCommitResult.CanonicalCommitted)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.recoveryDao.getMutationGroup(firstPrepared.group.groupId)?.state)

        val committedB = fixture.fileStore.readLongTermMemory().getOrThrow()
        val returnPrepared = fixture.coordinator.prepareLocalMutation(
            operationKey = "legacy-personal-memory",
            targets = listOf(fixture.longTermTarget(committedB, contentA))
        )

        assertNotEquals(firstPrepared.group.groupId, returnPrepared.group.groupId)
        assertEquals(2L, returnPrepared.group.generation)
        assertTrue(fixture.coordinator.reconcile(returnPrepared) is MemoryMutationCommitResult.CanonicalCommitted)
        assertEquals(contentA.trimEnd() + "\n", fixture.fileStore.readLongTermMemory().getOrThrow())
    }

    @Test
    fun `prepared receipt commits base to target and advances durable state`() = runBlocking {
        val fixture = Fixture()
        val baseContent = fixture.fileStore.readLongTermMemory().getOrThrow()
        val targetContent = "# ChatWithChat Memory\n\n## Preferences\n- Prefer exact recovery"

        val prepared = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-1",
            semanticBatchId = "batch-1",
            targets = listOf(fixture.longTermTarget(baseContent, targetContent))
        )

        assertEquals(MemoryMutationState.PREPARED, prepared.group.state)
        assertEquals(1L, prepared.group.generation)
        assertTrue(fixture.root.resolve(prepared.receipts.single().stagedTargetPath).isFile)

        val result = fixture.coordinator.reconcile(prepared)

        assertTrue(result is MemoryMutationCommitResult.CanonicalCommitted)
        result as MemoryMutationCommitResult.CanonicalCommitted
        assertTrue(result.hasPendingIndex)
        assertTrue(result.requiresSemanticAcknowledgement)
        assertEquals(targetContent + "\n", fixture.fileStore.readLongTermMemory().getOrThrow())
        val receipt = fixture.recoveryDao.getMutationReceipt(prepared.receipts.single().receiptId)
        assertNotNull(receipt)
        assertEquals(MemoryMutationState.INDEX_PENDING, receipt?.state)
        assertNotNull(receipt?.fileCommittedAt)
        val group = fixture.recoveryDao.getMutationGroup(prepared.group.groupId)
        assertEquals(MemoryMutationState.SEMANTIC_ACK_PENDING, group?.state)
        assertNotNull(group?.completedAt)
        val corpus = fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)
        assertEquals(prepared.group.generation, corpus?.generation)
        assertEquals(receipt?.targetSourceHash, corpus?.sourceHash)
        assertEquals(MemoryCorpusIndexStatus.PENDING, corpus?.indexStatus)
        assertEquals(listOf(prepared.group.generation), fixture.jobDao.jobs.map { it.generation })
        assertEquals(listOf(MemoryMaintenanceJobFamily.INDEX), fixture.workEnqueuer.works.map { it.family })

        val acknowledged = fixture.coordinator.acknowledgeSemanticCompletion(prepared.group.groupId)

        assertEquals(MemoryMutationState.INDEX_PENDING, acknowledged.group.state)
    }

    @Test
    fun `current target fast forwards a prepared receipt after process restart`() = runBlocking {
        val fixture = Fixture()
        val prepared = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-fast-forward",
            semanticBatchId = "batch-fast-forward",
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = fixture.fileStore.readLongTermMemory().getOrThrow(),
                    targetContent = "# ChatWithChat Memory\n\n- Canonical target already replaced"
                )
            )
        )
        val receipt = prepared.receipts.single()
        val externalCommit = fixture.fileStore.commitStagedMemoryFile(
            sourcePath = receipt.sourcePath,
            stagedTargetPath = receipt.stagedTargetPath,
            baseSourceHash = receipt.baseSourceHash,
            targetSourceHash = receipt.targetSourceHash
        ).getOrThrow()
        assertTrue(externalCommit is MemoryFileCommitOutcome.Committed)
        assertEquals(1, fixture.paths.backupDirectory.listFiles().orEmpty().size)

        val restartedCoordinator = fixture.newCoordinator(MemoryFileStore(fixture.paths, FIXED_CLOCK))
        val result = restartedCoordinator.reconcile(prepared)

        assertTrue(result is MemoryMutationCommitResult.CanonicalCommitted)
        assertEquals(MemoryMutationState.INDEX_PENDING, fixture.recoveryDao.getMutationReceipt(receipt.receiptId)?.state)
        assertEquals(receipt.targetSourceHash, fixture.fileStore.currentMemoryFileHash(receipt.sourcePath).getOrThrow())
        assertEquals(1, fixture.paths.backupDirectory.listFiles().orEmpty().size)
        assertEquals(1, fixture.jobDao.jobs.size)
    }

    @Test
    fun `current hash outside base and target records conflict without overwrite`() = runBlocking {
        val fixture = Fixture()
        val prepared = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-conflict",
            semanticBatchId = "batch-conflict",
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = fixture.fileStore.readLongTermMemory().getOrThrow(),
                    targetContent = "# ChatWithChat Memory\n\n- Approved target"
                )
            )
        )
        val newerContent = "# ChatWithChat Memory\n\n- Newer canonical content\n"
        fixture.paths.longTermMemoryFile.writeText(newerContent, StandardCharsets.UTF_8)

        val result = fixture.coordinator.reconcile(prepared)

        assertTrue(result is MemoryMutationCommitResult.Conflict)
        result as MemoryMutationCommitResult.Conflict
        assertEquals(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, result.sourcePath)
        assertEquals("canonical_source_hash_conflict", result.reason)
        assertEquals(newerContent, fixture.paths.longTermMemoryFile.readText(StandardCharsets.UTF_8))
        val receipt = fixture.recoveryDao.getMutationReceipt(prepared.receipts.single().receiptId)
        assertEquals(MemoryMutationState.CONFLICT, receipt?.state)
        assertEquals("canonical_source_hash_conflict", receipt?.lastError)
        assertEquals(MemoryMutationState.CONFLICT, fixture.recoveryDao.getMutationGroup(prepared.group.groupId)?.state)
        assertTrue(fixture.root.resolve(prepared.receipts.single().stagedTargetPath).isFile)
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertTrue(fixture.paths.backupDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `partial multi file commit is completed by incomplete receipt recovery`() = runBlocking {
        val fixture = Fixture()
        val dailySourcePath = "${MemoryFilePaths.DAILY_MEMORY_DIRECTORY_NAME}/${FIXED_DATE}.md"
        val longTermTarget = "# ChatWithChat Memory\n\n- Durable long-term target"
        val dailyTarget = "# $FIXED_DATE\n\n- Durable daily target"
        val prepared = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-multi-file",
            semanticBatchId = "batch-multi-file",
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = fixture.fileStore.readLongTermMemory().getOrThrow(),
                    targetContent = longTermTarget
                ),
                MemoryMutationTarget(
                    sourcePath = dailySourcePath,
                    baseContent = fixture.fileStore.readDailyMemory(FIXED_DATE).getOrThrow(),
                    targetContent = dailyTarget,
                    targetIndexFingerprint = null
                )
            )
        )
        val dailyReceipt = prepared.receipts.single { receipt -> receipt.sourcePath == dailySourcePath }
        fixture.fileStore.commitStagedMemoryFile(
            sourcePath = dailyReceipt.sourcePath,
            stagedTargetPath = dailyReceipt.stagedTargetPath,
            baseSourceHash = dailyReceipt.baseSourceHash,
            targetSourceHash = dailyReceipt.targetSourceHash
        ).getOrThrow()
        assertEquals(MemoryMutationState.PREPARED, fixture.recoveryDao.getMutationReceipt(dailyReceipt.receiptId)?.state)

        val repairResult = fixture.newCoordinator(MemoryFileStore(fixture.paths, FIXED_CLOCK)).reconcileIncomplete()

        assertEquals(1, repairResult.committedCount)
        assertEquals(0, repairResult.conflictCount)
        assertEquals(0, repairResult.failedCount)
        assertEquals(
            setOf(
                MemoryRecoveredSemanticMutation(
                    groupId = prepared.group.groupId,
                    semanticJobId = "semantic-job-multi-file",
                    generation = prepared.group.generation
                )
            ),
            repairResult.recoveredSemanticMutations
        )
        assertEquals(longTermTarget + "\n", fixture.paths.longTermMemoryFile.readText(StandardCharsets.UTF_8))
        assertEquals(dailyTarget + "\n", fixture.paths.dailyMemoryFile(FIXED_DATE).readText(StandardCharsets.UTF_8))
        val repairedReceipts = fixture.recoveryDao.getMutationReceipts(prepared.group.groupId)
        assertEquals(
            MemoryMutationState.INDEX_PENDING,
            repairedReceipts.single { receipt -> receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME }.state
        )
        assertEquals(MemoryMutationState.INDEXED, repairedReceipts.single { it.sourcePath == dailySourcePath }.state)
        assertEquals(
            MemoryMutationState.SEMANTIC_ACK_PENDING,
            fixture.recoveryDao.getMutationGroup(prepared.group.groupId)?.state
        )
        assertFalse(fixture.root.resolve(dailyReceipt.stagedTargetPath).exists())
        assertEquals(1, fixture.jobDao.jobs.size)
    }

    @Test
    fun `A to B to A allocates a distinct persistent generation`() = runBlocking {
        val fixture = Fixture()
        val contentA = "# ChatWithChat Memory\n\n- Revision A\n"
        val contentB = "# ChatWithChat Memory\n\n- Revision B\n"
        fixture.paths.longTermMemoryFile.writeText(contentA, StandardCharsets.UTF_8)
        val hashA = fixture.fileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrThrow()

        val mutationB = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-B",
            semanticBatchId = "batch-B",
            targets = listOf(fixture.longTermTarget(contentA, contentB))
        )
        fixture.coordinator.reconcile(mutationB)
        fixture.coordinator.acknowledgeSemanticCompletion(mutationB.group.groupId)
        val mutationA = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-A-again",
            semanticBatchId = "batch-A-again",
            targets = listOf(fixture.longTermTarget(contentB, contentA))
        )

        assertTrue(mutationA.group.generation > mutationB.group.generation)
        assertNotEquals(mutationB.group.idempotencyKey, mutationA.group.idempotencyKey)
        assertNotEquals(mutationB.receipts.single().idempotencyKey, mutationA.receipts.single().idempotencyKey)

        fixture.coordinator.reconcile(mutationA)
        fixture.coordinator.acknowledgeSemanticCompletion(mutationA.group.groupId)

        val finalHash = fixture.fileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrThrow()
        assertEquals(hashA, finalHash)
        assertEquals(contentA, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(mutationA.group.generation, fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)?.generation)
        assertEquals(
            listOf(mutationB.group.generation, mutationA.group.generation),
            fixture.jobDao.jobs.map { job -> job.generation }.sorted()
        )
    }

    @Test
    fun `older index pending generation becomes superseded without conflict`() = runBlocking {
        val fixture = Fixture()
        val contentA = fixture.fileStore.readLongTermMemory().getOrThrow()
        val contentB = "# ChatWithChat Memory\n\n- Revision B\n"
        val contentC = "# ChatWithChat Memory\n\n- Revision C\n"
        val olderMutation = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-older",
            semanticBatchId = "batch-older",
            targets = listOf(fixture.longTermTarget(contentA, contentB))
        )
        fixture.coordinator.reconcile(olderMutation)
        fixture.coordinator.acknowledgeSemanticCompletion(olderMutation.group.groupId)
        assertEquals(
            MemoryMutationState.INDEX_PENDING,
            fixture.recoveryDao.getMutationReceipt(olderMutation.receipts.single().receiptId)?.state
        )
        val newerMutation = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-newer",
            semanticBatchId = "batch-newer",
            targets = listOf(fixture.longTermTarget(contentB, contentC))
        )
        fixture.coordinator.reconcile(newerMutation)
        val scheduledJobCount = fixture.jobDao.jobs.size
        val enqueuedWorkCount = fixture.workEnqueuer.works.size

        val replayResult = fixture.coordinator.reconcile(olderMutation)

        assertTrue(replayResult is MemoryMutationCommitResult.CanonicalCommitted)
        replayResult as MemoryMutationCommitResult.CanonicalCommitted
        assertFalse(replayResult.hasPendingIndex)
        assertEquals(MemoryMutationState.SUPERSEDED, replayResult.mutation.group.state)
        assertEquals(MemoryMutationState.SUPERSEDED, replayResult.mutation.receipts.single().state)
        assertEquals(
            "superseded_by_newer_corpus_generation",
            fixture.recoveryDao.getMutationGroup(olderMutation.group.groupId)?.lastError
        )
        assertEquals(contentC, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(
            newerMutation.group.generation,
            fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)?.generation
        )
        assertFalse(fixture.root.resolve(olderMutation.receipts.single().stagedTargetPath).exists())
        assertEquals(scheduledJobCount, fixture.jobDao.jobs.size)
        assertEquals(enqueuedWorkCount, fixture.workEnqueuer.works.size)
    }

    @Test
    fun `superseded acknowledgement remains recoverable across a second process death`() = runBlocking {
        val fixture = Fixture()
        val contentA = fixture.fileStore.readLongTermMemory().getOrThrow()
        val contentB = "# ChatWithChat Memory\n\n- Revision B\n"
        val contentC = "# ChatWithChat Memory\n\n- Revision C\n"
        val olderMutation = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-ack-pending-older",
            semanticBatchId = "batch-ack-pending-older",
            targets = listOf(fixture.longTermTarget(contentA, contentB))
        )
        fixture.coordinator.reconcile(olderMutation)
        val newerMutation = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-ack-pending-newer",
            semanticBatchId = "batch-ack-pending-newer",
            targets = listOf(fixture.longTermTarget(contentB, contentC))
        )
        fixture.coordinator.reconcile(newerMutation)
        fixture.coordinator.acknowledgeSemanticCompletion(newerMutation.group.groupId)

        val firstReplay = fixture.coordinator.reconcile(olderMutation)
        assertEquals(
            MemoryMutationState.SEMANTIC_ACK_PENDING,
            fixture.recoveryDao.getMutationGroup(olderMutation.group.groupId)?.state
        )
        assertEquals(
            MemoryMutationState.SUPERSEDED,
            fixture.recoveryDao.getMutationReceipts(olderMutation.group.groupId).single().state
        )
        val secondReplay = fixture.coordinator.reconcile(olderMutation)

        assertTrue(firstReplay is MemoryMutationCommitResult.CanonicalCommitted)
        assertTrue(
            "second=${secondReplay::class.simpleName} group=${fixture.recoveryDao.getMutationGroup(olderMutation.group.groupId)?.state} " +
                "receipt=${fixture.recoveryDao.getMutationReceipts(olderMutation.group.groupId).single().state}",
            secondReplay is MemoryMutationCommitResult.CanonicalCommitted
        )
        firstReplay as MemoryMutationCommitResult.CanonicalCommitted
        secondReplay as MemoryMutationCommitResult.CanonicalCommitted
        assertTrue(firstReplay.requiresSemanticAcknowledgement)
        assertTrue(secondReplay.requiresSemanticAcknowledgement)
        assertEquals(MemoryMutationState.SEMANTIC_ACK_PENDING, secondReplay.mutation.group.state)
        assertEquals(MemoryMutationState.SUPERSEDED, secondReplay.mutation.receipts.single().state)

        val acknowledged = fixture.coordinator.acknowledgeSemanticCompletion(olderMutation.group.groupId)

        assertEquals(MemoryMutationState.SUPERSEDED, acknowledged.group.state)
        assertEquals(contentC, fixture.fileStore.readLongTermMemory().getOrThrow())
    }

    @Test
    fun `empty semantic result persists an acknowledgement marker without file receipts`() = runBlocking {
        val fixture = Fixture()

        val prepared = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-empty",
            semanticBatchId = "batch-empty",
            targets = emptyList()
        )
        val result = fixture.coordinator.reconcile(prepared)

        assertTrue(prepared.receipts.isEmpty())
        assertTrue(result is MemoryMutationCommitResult.CanonicalCommitted)
        result as MemoryMutationCommitResult.CanonicalCommitted
        assertFalse(result.hasPendingIndex)
        assertTrue(result.requiresSemanticAcknowledgement)
        assertEquals(MemoryMutationState.SEMANTIC_ACK_PENDING, result.mutation.group.state)
        assertTrue(fixture.jobDao.jobs.isEmpty())

        val acknowledged = fixture.coordinator.acknowledgeSemanticCompletion(prepared.group.groupId)

        assertEquals(MemoryMutationState.INDEXED, acknowledged.group.state)
    }

    @Test
    fun `orphan stage from a crash before prepared is replaced by the next attempt`() = runBlocking {
        val fixture = Fixture()
        val semanticJobId = "semantic-job-orphan-stage"
        val semanticBatchId = "batch-orphan-stage"
        val sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        val groupId = "mutation_${"$semanticJobId|$semanticBatchId".sha256Utf8().take(24)}"
        val receiptId = "${groupId}_receipt_${sourcePath.sha256Utf8().take(16)}"
        val baseContent = fixture.fileStore.readLongTermMemory().getOrThrow()
        val orphan = fixture.fileStore.stageMemoryFile(
            sourcePath = sourcePath,
            content = "# ChatWithChat Memory\n\n- Orphan target",
            stagingId = receiptId
        ).getOrThrow()

        val prepared = fixture.coordinator.prepare(
            semanticJobId = semanticJobId,
            semanticBatchId = semanticBatchId,
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = baseContent,
                    targetContent = "# ChatWithChat Memory\n\n- Retried approved target"
                )
            )
        )

        assertEquals(receiptId, prepared.receipts.single().receiptId)
        assertNotEquals(orphan.targetSourceHash, prepared.receipts.single().targetSourceHash)
        assertTrue(fixture.root.resolve(prepared.receipts.single().stagedTargetPath).isFile)
    }

    @Test
    fun `missing staged target becomes stable terminal conflict without repair churn`() = runBlocking {
        val fixture = Fixture()
        val canonicalBefore = fixture.fileStore.readLongTermMemory().getOrThrow()
        val prepared = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-repair-retry",
            semanticBatchId = "batch-repair-retry",
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = fixture.fileStore.readLongTermMemory().getOrThrow(),
                    targetContent = "# ChatWithChat Memory\n\n- Repair retry target"
                )
            )
        )
        val receiptBefore = prepared.receipts.single()
        Files.delete(fixture.root.resolve(receiptBefore.stagedTargetPath).toPath())
        val service = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.coordinator,
            turnBatchDao = InMemoryMemoryTurnBatchDao(),
            maintenanceScheduler = MemoryMaintenanceScheduler(fixture.jobDao, FIXED_CLOCK),
            clock = FIXED_CLOCK
        )

        val firstRecovery = service.recoverIncomplete()
        val terminalReceipt = checkNotNull(fixture.recoveryDao.getMutationReceipt(receiptBefore.receiptId))
        val terminalGroup = checkNotNull(fixture.recoveryDao.getMutationGroup(prepared.group.groupId))
        val secondRecovery = service.recoverIncomplete()

        assertEquals(1, firstRecovery.conflictCount)
        assertEquals(0, firstRecovery.failedCount)
        assertEquals(1, firstRecovery.recoveredSemanticCount)
        assertTrue(firstRecovery.retryGenerations.isEmpty())
        assertEquals(MemoryMutationState.CONFLICT, terminalReceipt.state)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, terminalReceipt.lastError)
        assertEquals(receiptBefore.attempts, terminalReceipt.attempts)
        assertEquals(receiptBefore.rowVersion + 1, terminalReceipt.rowVersion)
        assertEquals(MemoryMutationState.CONFLICT, terminalGroup.state)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, terminalGroup.lastError)
        assertNotNull(terminalGroup.completedAt)
        assertEquals(prepared.group.rowVersion + 1, terminalGroup.rowVersion)
        assertEquals(canonicalBefore, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(null, fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertTrue(fixture.workEnqueuer.works.isEmpty())

        assertEquals(0, secondRecovery.conflictCount)
        assertEquals(0, secondRecovery.failedCount)
        assertEquals(0, secondRecovery.recoveredSemanticCount)
        assertTrue(secondRecovery.retryGenerations.isEmpty())
        assertEquals(terminalReceipt, fixture.recoveryDao.getMutationReceipt(receiptBefore.receiptId))
        assertEquals(terminalGroup, fixture.recoveryDao.getMutationGroup(prepared.group.groupId))
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertTrue(fixture.workEnqueuer.works.isEmpty())
    }

    @Test
    fun `persisted conflict resumes source job finalization exactly once after process death`() = runBlocking {
        val fixture = Fixture()
        val statusEvents = mutableListOf<MemoryMaintenanceStatusChangedEvent>()
        val scheduler = MemoryMaintenanceScheduler(
            jobDao = fixture.jobDao,
            clock = FIXED_CLOCK,
            eventSink = object : MemoryMaintenanceEventSink {
                override suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent) {
                    statusEvents += event
                }
            }
        )
        val sourceJob = scheduler.enqueue(
            type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            idempotencyKey = "persisted-conflict-source",
            payloadJson = "{}",
            jobId = "semantic-job-persisted-conflict"
        )
        scheduler.markRecoveredConflict(sourceJob.jobId, "old_generic_error")
        val staleSourceJob = checkNotNull(fixture.jobDao.getById(sourceJob.jobId))
        val prepared = fixture.coordinator.prepare(
            semanticJobId = sourceJob.jobId,
            semanticBatchId = "batch-persisted-conflict",
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = fixture.fileStore.readLongTermMemory().getOrThrow(),
                    targetContent = "# ChatWithChat Memory\n\n- Unrecoverable target"
                )
            )
        )
        val receiptBefore = prepared.receipts.single()
        Files.delete(fixture.root.resolve(receiptBefore.stagedTargetPath).toPath())

        val conflict = fixture.coordinator.reconcile(prepared)

        assertTrue(conflict is MemoryMutationCommitResult.Conflict)
        val terminalReceipt = checkNotNull(fixture.recoveryDao.getMutationReceipt(receiptBefore.receiptId))
        val terminalGroup = checkNotNull(fixture.recoveryDao.getMutationGroup(prepared.group.groupId))
        assertEquals(MemoryMutationState.CONFLICT, terminalReceipt.state)
        assertEquals(MemoryMutationState.CONFLICT, terminalGroup.state)
        assertEquals("old_generic_error", fixture.jobDao.getById(sourceJob.jobId)?.lastError)
        assertEquals(1, statusEvents.size)

        val recoveryService = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.coordinator,
            turnBatchDao = InMemoryMemoryTurnBatchDao(),
            maintenanceScheduler = scheduler,
            clock = FIXED_CLOCK
        )
        val firstRecovery = recoveryService.recoverIncomplete()
        val finalizedSourceJob = checkNotNull(fixture.jobDao.getById(sourceJob.jobId))
        val secondRecovery = recoveryService.recoverIncomplete()

        assertEquals(0, firstRecovery.conflictCount)
        assertEquals(0, firstRecovery.failedCount)
        assertEquals(1, firstRecovery.recoveredSemanticCount)
        assertTrue(firstRecovery.retryGenerations.isEmpty())
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, finalizedSourceJob.status)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, finalizedSourceJob.lastError)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, finalizedSourceJob.blockedReason)
        assertEquals(staleSourceJob.rowVersion + 1, finalizedSourceJob.rowVersion)
        assertEquals(2, statusEvents.size)

        assertEquals(0, secondRecovery.conflictCount)
        assertEquals(0, secondRecovery.failedCount)
        assertEquals(0, secondRecovery.recoveredSemanticCount)
        assertTrue(secondRecovery.retryGenerations.isEmpty())
        assertEquals(finalizedSourceJob, fixture.jobDao.getById(sourceJob.jobId))
        assertEquals(terminalReceipt, fixture.recoveryDao.getMutationReceipt(receiptBefore.receiptId))
        assertEquals(terminalGroup, fixture.recoveryDao.getMutationGroup(prepared.group.groupId))
        assertEquals(2, statusEvents.size)
        assertTrue(
            fixture.jobDao.jobs.none { job ->
                job.type == MemoryMaintenanceJobType.RECONCILE_MEMORY_MUTATIONS
            }
        )
        assertTrue(fixture.workEnqueuer.works.isEmpty())
    }

    @Test
    fun `claimed terminal conflict is complete before startup recovery`() = runBlocking {
        val fixture = Fixture()
        val statusEvents = mutableListOf<MemoryMaintenanceStatusChangedEvent>()
        val scheduler = MemoryMaintenanceScheduler(
            jobDao = fixture.jobDao,
            clock = FIXED_CLOCK,
            eventSink = object : MemoryMaintenanceEventSink {
                override suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent) {
                    statusEvents += event
                }
            }
        )
        val sourceJob = scheduler.enqueue(
            type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            idempotencyKey = "claimed-terminal-conflict",
            payloadJson = "{}",
            jobId = "semantic-job-claimed-terminal"
        )
        val claimed = checkNotNull(
            scheduler.claimNextRunnable(MemoryMaintenanceJobFamily.SEMANTIC, "semantic-owner")
        )
        assertEquals(sourceJob.jobId, claimed.jobId)
        val prepared = fixture.coordinator.prepare(
            semanticJobId = sourceJob.jobId,
            semanticBatchId = "batch-claimed-terminal",
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = fixture.fileStore.readLongTermMemory().getOrThrow(),
                    targetContent = "# ChatWithChat Memory\n\n- Terminal conflict"
                )
            )
        )
        Files.delete(fixture.root.resolve(prepared.receipts.single().stagedTargetPath).toPath())
        val conflict = fixture.coordinator.reconcile(prepared) as MemoryMutationCommitResult.Conflict
        val terminal = scheduler.markFailedTerminal(claimed, conflict.reason)
        val terminalEventCount = statusEvents.size
        var finalizerCalls = 0
        val turnBatchDao = object : MemoryTurnBatchDao by InMemoryMemoryTurnBatchDao() {
            override suspend fun completeClaimedBatch(jobId: String, updatedAt: Long): Boolean {
                finalizerCalls += 1
                return true
            }
        }
        val recoveryService = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.coordinator,
            turnBatchDao = turnBatchDao,
            maintenanceScheduler = scheduler,
            clock = FIXED_CLOCK
        )

        val firstRecovery = recoveryService.recoverIncomplete()
        val afterFirstRecovery = checkNotNull(fixture.jobDao.getById(sourceJob.jobId))
        val eventCountAfterFirstRecovery = statusEvents.size
        val finalizerCallsAfterFirstRecovery = finalizerCalls
        val secondRecovery = recoveryService.recoverIncomplete()
        val afterSecondRecovery = checkNotNull(fixture.jobDao.getById(sourceJob.jobId))

        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, terminal.status)
        assertEquals(conflict.reason, terminal.lastError)
        assertEquals(conflict.reason, terminal.blockedReason)
        assertEquals(null, terminal.startedAt)
        assertEquals(claimed.rowVersion + 1, terminal.rowVersion)
        assertEquals(0, firstRecovery.recoveredSemanticCount)
        assertEquals(terminal, afterFirstRecovery)
        assertEquals(terminalEventCount, eventCountAfterFirstRecovery)
        assertEquals(0, finalizerCallsAfterFirstRecovery)
        assertEquals(0, secondRecovery.recoveredSemanticCount)
        assertEquals(terminal, afterSecondRecovery)
        assertEquals(eventCountAfterFirstRecovery, statusEvents.size)
        assertEquals(finalizerCallsAfterFirstRecovery, finalizerCalls)
        assertEquals(0, finalizerCalls)
        assertEquals(2, terminalEventCount)
    }

    @Test
    fun `invalid and mismatched staged targets persist their exact terminal reason`() = runBlocking {
        val scenarios = listOf<Pair<String, (File) -> Unit>>(
            MEMORY_MUTATION_UNRECOVERABLE_STAGING_INVALID to { stagedFile ->
                Files.delete(stagedFile.toPath())
                Files.createDirectory(stagedFile.toPath())
            },
            MEMORY_MUTATION_UNRECOVERABLE_STAGING_HASH_MISMATCH to { stagedFile ->
                stagedFile.writeText("corrupted staged bytes\n", StandardCharsets.UTF_8)
            }
        )

        scenarios.forEachIndexed { index, (expectedReason, corrupt) ->
            val fixture = Fixture()
            val prepared = fixture.coordinator.prepare(
                semanticJobId = "semantic-job-terminal-$index",
                semanticBatchId = "batch-terminal-$index",
                targets = listOf(
                    fixture.longTermTarget(
                        baseContent = fixture.fileStore.readLongTermMemory().getOrThrow(),
                        targetContent = "# ChatWithChat Memory\n\n- Terminal target $index"
                    )
                )
            )
            val receipt = prepared.receipts.single()
            corrupt(fixture.root.resolve(receipt.stagedTargetPath))

            val result = fixture.coordinator.reconcile(prepared)

            assertTrue(result is MemoryMutationCommitResult.Conflict)
            assertEquals(expectedReason, (result as MemoryMutationCommitResult.Conflict).reason)
            assertEquals(expectedReason, fixture.recoveryDao.getMutationReceipt(receipt.receiptId)?.lastError)
            assertEquals(expectedReason, fixture.recoveryDao.getMutationGroup(prepared.group.groupId)?.lastError)
            assertEquals(null, fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))
            assertTrue(fixture.jobDao.jobs.isEmpty())
            assertTrue(fixture.workEnqueuer.works.isEmpty())
        }
    }

    @Test
    fun `local receipt conflict creates no semantic finalization or notification job`() = runBlocking {
        val fixture = Fixture()
        val baseContent = fixture.fileStore.readLongTermMemory().getOrThrow()
        val prepared = fixture.coordinator.prepareLocalMutation(
            operationKey = "local-missing-stage",
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = baseContent,
                    targetContent = "# ChatWithChat Memory\n\n- Local target"
                )
            )
        )
        val receipt = prepared.receipts.single()
        Files.delete(fixture.root.resolve(receipt.stagedTargetPath).toPath())
        val service = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.coordinator,
            turnBatchDao = InMemoryMemoryTurnBatchDao(),
            maintenanceScheduler = MemoryMaintenanceScheduler(fixture.jobDao, FIXED_CLOCK),
            clock = FIXED_CLOCK
        )

        val recovery = service.recoverIncomplete()

        assertEquals(1, recovery.conflictCount)
        assertEquals(0, recovery.failedCount)
        assertEquals(0, recovery.recoveredSemanticCount)
        assertTrue(recovery.retryGenerations.isEmpty())
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, fixture.recoveryDao.getMutationGroup(prepared.group.groupId)?.lastError)
        assertEquals(baseContent, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertTrue(fixture.jobDao.jobs.isEmpty())
        assertTrue(fixture.workEnqueuer.works.isEmpty())
    }

    @Test
    fun `transient staging io failure stays prepared and schedules bounded repair`() = runBlocking {
        val fixture = Fixture()
        val prepared = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-transient-io",
            semanticBatchId = "batch-transient-io",
            targets = listOf(
                fixture.longTermTarget(
                    baseContent = fixture.fileStore.readLongTermMemory().getOrThrow(),
                    targetContent = "# ChatWithChat Memory\n\n- Retry after transient staging I/O"
                )
            )
        )
        val receiptBefore = prepared.receipts.single()
        Files.delete(fixture.root.resolve(receiptBefore.stagedTargetPath).toPath())
        Files.delete(fixture.paths.stagingDirectory.toPath())
        fixture.paths.stagingDirectory.writeText("temporarily unavailable", StandardCharsets.UTF_8)
        val service = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.coordinator,
            turnBatchDao = InMemoryMemoryTurnBatchDao(),
            maintenanceScheduler = MemoryMaintenanceScheduler(fixture.jobDao, FIXED_CLOCK),
            clock = FIXED_CLOCK
        )

        val recovery = service.recoverIncomplete()

        assertEquals(1, recovery.failedCount)
        assertEquals(setOf(prepared.group.generation), recovery.retryGenerations)
        assertEquals(receiptBefore, fixture.recoveryDao.getMutationReceipt(receiptBefore.receiptId))
        assertEquals(prepared.group, fixture.recoveryDao.getMutationGroup(prepared.group.groupId))
        assertEquals(null, fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))
        assertEquals(
            listOf(MemoryMaintenanceJobType.RECONCILE_MEMORY_MUTATIONS),
            fixture.jobDao.jobs.map { job -> job.type }
        )
        assertTrue(fixture.workEnqueuer.works.isEmpty())
    }

    @Test
    fun `partial multi file conflict preserves canonical and allows bootstrap forward`() = runBlocking {
        val longTermBefore = fixtureLongTermContent()
        val fixture = Fixture()
        fixture.fileStore.replaceLongTermMemory(longTermBefore).getOrThrow()
        val dailyPath = "${MemoryFilePaths.DAILY_MEMORY_DIRECTORY_NAME}/${FIXED_DATE}.md"
        val dailyBefore = fixture.fileStore.readDailyMemory(FIXED_DATE).getOrThrow()
        val longTermTarget = "# ChatWithChat Memory\n\n- Canonical partial commit survives"
        val prepared = fixture.coordinator.prepare(
            semanticJobId = "semantic-job-partial-conflict",
            semanticBatchId = "batch-partial-conflict",
            targets = listOf(
                fixture.longTermTarget(longTermBefore.trimEnd() + "\n", longTermTarget),
                MemoryMutationTarget(
                    sourcePath = dailyPath,
                    baseContent = dailyBefore,
                    targetContent = dailyBefore + "- Unrecoverable daily target\n",
                    targetIndexFingerprint = null
                )
            )
        )
        val dailyReceipt = prepared.receipts.single { receipt -> receipt.sourcePath == dailyPath }
        Files.delete(fixture.root.resolve(dailyReceipt.stagedTargetPath).toPath())

        val conflict = fixture.coordinator.reconcile(prepared)

        assertTrue(conflict is MemoryMutationCommitResult.Conflict)
        assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, (conflict as MemoryMutationCommitResult.Conflict).reason)
        val committedLongTerm = fixture.fileStore.readLongTermMemory().getOrThrow()
        assertEquals(longTermTarget + "\n", committedLongTerm)
        assertEquals(dailyBefore, fixture.fileStore.readDailyMemory(FIXED_DATE).getOrThrow())
        assertEquals(null, fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY))
        assertTrue(fixture.jobDao.jobs.isEmpty())

        val snapshotSource = MemoryCorpusSnapshotter(fixture.fileStore, MemoryChunker())
        val unusedProvider = object : MemoryEmbeddingProvider {
            override val descriptor = MemoryVectorIndexDefaults.embeddingDescriptor

            override suspend fun availability(): MemoryEmbeddingAvailability =
                error("Stale corpus state must fail closed before provider access")

            override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> =
                error("Stale corpus state must fail closed before document embedding")

            override suspend fun embedQuery(text: String): Result<FloatArray> =
                error("Stale corpus state must fail closed before query embedding")
        }
        val unusedVectorStore = Proxy.newProxyInstance(
            MemoryVectorStore::class.java.classLoader,
            arrayOf(MemoryVectorStore::class.java)
        ) { _, method, _ ->
            error("Stale corpus state must fail closed before vector store ${method.name}")
        } as MemoryVectorStore
        var repairRequests = 0
        val hybridRetriever = HybridMemoryRetriever(
            snapshotSource = snapshotSource,
            lexicalRetriever = MarkdownLexicalRetriever(snapshotSource),
            vectorStore = unusedVectorStore,
            embeddingCapabilitySource = MemoryEmbeddingCapabilitySource {
                MemoryEmbeddingCapability.Ready(unusedProvider, MemoryVectorIndexDefaults.configuration)
            },
            vectorRecallStateSource = RoomMemoryVectorRecallStateSource(fixture.recoveryDao),
            repairTrigger = object : MemoryVectorRecallRepairTrigger {
                override fun requestRepair() {
                    repairRequests += 1
                }
            }
        )

        val recalled = hybridRetriever.retrieve(
            MemoryRetrievalRequest(
                corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
                query = "canonical partial commit survives",
                limit = 2,
                candidateLimit = 4,
                tokenBudget = 200,
                strategy = MemoryRetrievalStrategy.HYBRID
            )
        ).getOrThrow()

        assertTrue(recalled.any { result -> result.text.contains("Canonical partial commit survives") })
        assertEquals(1, repairRequests)

        val bootstrap = fixture.coordinator.prepareLocalIndexBootstrap(
            sourceContent = committedLongTerm,
            sourceHash = committedLongTerm.toByteArray(Charsets.UTF_8).sha256Hex(),
            targetIndexFingerprint = VALID_FINGERPRINT,
            observedCorpusGeneration = 0
        )
        val bootstrapResult = fixture.coordinator.reconcile(bootstrap)

        assertTrue(bootstrapResult is MemoryMutationCommitResult.CanonicalCommitted)
        assertEquals(committedLongTerm, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(MemoryCorpusIndexStatus.PENDING, fixture.recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)?.indexStatus)
        assertEquals(
            listOf(MemoryMaintenanceJobType.SYNC_VECTOR_INDEX),
            fixture.jobDao.jobs.map { job -> job.type }
        )
    }

    @Test
    fun `recovery persists continuation and drains more than one repair page`() = runBlocking {
        val fixture = Fixture()
        repeat(101) { index ->
            fixture.coordinator.prepare(
                semanticJobId = "semantic-job-page-$index",
                semanticBatchId = "batch-page-$index",
                targets = emptyList()
            )
        }
        val service = MemoryMutationRecoveryService(
            memoryMutationCoordinator = fixture.coordinator,
            turnBatchDao = InMemoryMemoryTurnBatchDao(),
            maintenanceScheduler = MemoryMaintenanceScheduler(fixture.jobDao, FIXED_CLOCK),
            clock = FIXED_CLOCK
        )

        val firstPage = service.recoverIncomplete()

        assertTrue(firstPage.hasMore)
        assertEquals(100, firstPage.recoveredSemanticCount)
        assertEquals(setOf(101L), firstPage.retryGenerations)
        assertEquals(
            1,
            fixture.jobDao.jobs.count { job ->
                job.type == MemoryMaintenanceJobType.RECONCILE_MEMORY_MUTATIONS && job.generation == 101L
            }
        )

        val secondPage = service.recoverIncomplete()

        assertFalse(secondPage.hasMore)
        assertEquals(1, secondPage.recoveredSemanticCount)
        assertEquals(0, secondPage.failedCount)
    }

    private class Fixture {
        val root: File = Files.createTempDirectory("memory-mutation-coordinator-test").toFile()
        val paths = MemoryFilePaths(root)
        val fileStore = MemoryFileStore(paths, FIXED_CLOCK)
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val workEnqueuer = RecordingWorkEnqueuer()
        val coordinator = newCoordinator(fileStore)

        init {
            fileStore.ensureStore().getOrThrow()
        }

        fun longTermTarget(baseContent: String, targetContent: String): MemoryMutationTarget =
            MemoryMutationTarget(
                sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                baseContent = baseContent,
                targetContent = targetContent,
                targetIndexFingerprint = VALID_FINGERPRINT
            )

        fun newCoordinator(store: MemoryFileStore): MemoryMutationCoordinator =
            MemoryMutationCoordinator(
                recoveryDao = recoveryDao,
                memoryFileStore = store,
                maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK),
                workEnqueuer = workEnqueuer,
                clock = FIXED_CLOCK
            )
    }

    private companion object {
        val FIXED_DATE: LocalDate = LocalDate.parse("2026-07-09")
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-09T10:20:30Z"), ZoneOffset.UTC)
        val CHAT_RECALL_CORPUS_KEY: String = MemoryCorpus.CHAT_RECALL_LONG_TERM.name.lowercase()
        val VALID_FINGERPRINT: String = "a".repeat(64)

        fun fixtureLongTermContent(): String = "# ChatWithChat Memory\n\n- Canonical base\n"
    }
}
