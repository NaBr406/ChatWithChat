package cn.nabr.chatwithchat.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryBackupRestoreSimulationInstrumentedTest {

    @Test
    fun restoredPreparedReceiptWithoutStaging_terminatesOnlyOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val harness = TestHarness.create(context, "missing-${System.nanoTime()}")
        try {
            val initial = harness.open()
            val sourceJob = initial.enqueueSourceJob("restore-missing-source")
            val canonicalBefore = initial.fileStore.readLongTermMemory().getOrThrow()
            val prepared = initial.coordinator.prepare(
                semanticJobId = sourceJob.jobId,
                semanticBatchId = "restore-missing-batch",
                targets = listOf(initial.longTermTarget(canonicalBefore, "# ChatWithChat Memory\n\n- Lost restored target"))
            )
            val receiptBefore = prepared.receipts.single()
            Files.delete(harness.memoryRoot.resolve(receiptBefore.stagedTargetPath).toPath())
            initial.markSourceJobTerminal(sourceJob.jobId, "old_generic_restore_error")
            initial.close()

            val first = harness.open()
            val firstRecovery = first.recoveryService.recoverIncomplete()
            val terminalReceipt = checkNotNull(first.database.memoryRecoveryDao().getMutationReceipt(receiptBefore.receiptId))
            val terminalGroup = checkNotNull(first.database.memoryRecoveryDao().getMutationGroup(prepared.group.groupId))
            val terminalJob = checkNotNull(first.database.memoryMaintenanceJobDao().getById(sourceJob.jobId))

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
            assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, terminalJob.status)
            assertEquals(MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING, terminalJob.lastError)
            assertEquals(1, first.eventSink.events.size)
            assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, first.eventSink.events.single().newStatus)
            assertEquals(canonicalBefore, first.fileStore.readLongTermMemory().getOrThrow())
            assertEquals(null, first.database.memoryRecoveryDao().getCorpusState(CHAT_RECALL_CORPUS_KEY))
            assertTrue(first.repairJobs().isEmpty())
            assertTrue(first.indexJobs().isEmpty())
            assertTrue(first.workEnqueuer.works.isEmpty())
            first.close()

            val second = harness.open()
            val secondRecovery = second.recoveryService.recoverIncomplete()

            assertEquals(0, secondRecovery.conflictCount)
            assertEquals(0, secondRecovery.failedCount)
            assertEquals(0, secondRecovery.recoveredSemanticCount)
            assertTrue(secondRecovery.retryGenerations.isEmpty())
            assertEquals(terminalReceipt, second.database.memoryRecoveryDao().getMutationReceipt(receiptBefore.receiptId))
            assertEquals(terminalGroup, second.database.memoryRecoveryDao().getMutationGroup(prepared.group.groupId))
            assertEquals(terminalJob, second.database.memoryMaintenanceJobDao().getById(sourceJob.jobId))
            assertTrue(second.eventSink.events.isEmpty())
            assertTrue(second.repairJobs().isEmpty())
            assertTrue(second.indexJobs().isEmpty())
            assertTrue(second.workEnqueuer.works.isEmpty())
            second.close()
        } finally {
            harness.delete()
        }
    }

    @Test
    fun restoredPreparedReceiptWithCanonicalTarget_completesWithoutStaging() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val harness = TestHarness.create(context, "committed-${System.nanoTime()}")
        try {
            val initial = harness.open()
            val sourceJob = initial.enqueueSourceJob("restore-committed-source")
            val canonicalBefore = initial.fileStore.readLongTermMemory().getOrThrow()
            val target = "# ChatWithChat Memory\n\n- Canonical target survived restore"
            val prepared = initial.coordinator.prepare(
                semanticJobId = sourceJob.jobId,
                semanticBatchId = "restore-committed-batch",
                targets = listOf(initial.longTermTarget(canonicalBefore, target))
            )
            val receipt = prepared.receipts.single()
            assertTrue(initial.fileStore.commitStagedMemoryFile(receipt.toStagedMemoryFile()).getOrThrow() is MemoryFileCommitOutcome.Committed)
            assertTrue(initial.fileStore.cleanupStagedTarget(receipt.stagedTargetPath).getOrThrow())
            initial.close()

            val restored = harness.open()
            val recovery = restored.recoveryService.recoverIncomplete()

            assertEquals(1, recovery.committedCount)
            assertEquals(0, recovery.conflictCount)
            assertEquals(0, recovery.failedCount)
            assertEquals(1, recovery.recoveredSemanticCount)
            assertEquals(target.trimEnd() + "\n", restored.fileStore.readLongTermMemory().getOrThrow())
            assertEquals(MemoryMutationState.INDEX_PENDING, restored.database.memoryRecoveryDao().getMutationReceipt(receipt.receiptId)?.state)
            assertEquals(MemoryMutationState.INDEX_PENDING, restored.database.memoryRecoveryDao().getMutationGroup(prepared.group.groupId)?.state)
            assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, restored.database.memoryMaintenanceJobDao().getById(sourceJob.jobId)?.status)
            assertEquals(1, restored.indexJobs().size)
            assertTrue(restored.repairJobs().isEmpty())
            restored.close()
        } finally {
            harness.delete()
        }
    }

    private class TestHarness private constructor(
        private val context: Context,
        private val databaseName: String,
        val memoryRoot: File
    ) {
        fun open(): OpenHarness = OpenHarness(
            database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build(),
            memoryRoot = memoryRoot
        )

        fun delete() {
            context.deleteDatabase(databaseName)
            memoryRoot.deleteRecursively()
        }

        companion object {
            fun create(context: Context, suffix: String): TestHarness {
                val databaseName = "memory-backup-restore-$suffix.db"
                val memoryRoot = File(context.filesDir, "memory_backup_restore_simulation/$suffix")
                context.deleteDatabase(databaseName)
                memoryRoot.deleteRecursively()
                return TestHarness(context, databaseName, memoryRoot)
            }
        }
    }

    private class OpenHarness(
        val database: ChatDatabaseV2,
        memoryRoot: File
    ) {
        val fileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), FIXED_CLOCK)
        val eventSink = RecordingEventSink()
        val workEnqueuer = RecordingWorkEnqueuer()
        private val scheduler = MemoryMaintenanceScheduler(database.memoryMaintenanceJobDao(), FIXED_CLOCK, eventSink)
        val coordinator = MemoryMutationCoordinator(
            recoveryDao = database.memoryRecoveryDao(),
            memoryFileStore = fileStore,
            maintenanceScheduler = scheduler,
            workEnqueuer = workEnqueuer,
            clock = FIXED_CLOCK
        )
        val recoveryService = MemoryMutationRecoveryService(
            memoryMutationCoordinator = coordinator,
            turnBatchDao = database.memoryTurnBatchDao(),
            maintenanceScheduler = scheduler,
            clock = FIXED_CLOCK
        )

        init {
            fileStore.ensureStore().getOrThrow()
        }

        suspend fun enqueueSourceJob(jobId: String) = scheduler.enqueue(
            type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            idempotencyKey = jobId,
            payloadJson = "{}",
            jobId = jobId
        )

        fun longTermTarget(base: String, target: String) = MemoryMutationTarget(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            baseContent = base,
            targetContent = target,
            targetIndexFingerprint = VALID_INDEX_FINGERPRINT
        )

        suspend fun repairJobs() = database.memoryMaintenanceJobDao().getByTypeAndStatuses(
            MemoryMaintenanceJobType.RECONCILE_MEMORY_MUTATIONS,
            ALL_JOB_STATUSES
        )

        suspend fun indexJobs() = database.memoryMaintenanceJobDao().getByTypeAndStatuses(
            MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
            ALL_JOB_STATUSES
        )

        suspend fun markSourceJobTerminal(jobId: String, reason: String) {
            scheduler.markRecoveredConflict(jobId, reason)
        }

        fun close() {
            database.close()
        }
    }

    private class RecordingEventSink : MemoryMaintenanceEventSink {
        val events = mutableListOf<MemoryMaintenanceStatusChangedEvent>()

        override suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent) {
            events += event
        }
    }

    private class RecordingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
        val works = mutableListOf<String>()

        override fun enqueueWork(family: String, delaySeconds: Long) {
            works += family
        }
    }

    private fun cn.nabr.chatwithchat.data.database.entity.MemoryMutationReceipt.toStagedMemoryFile() = StagedMemoryFile(
        sourcePath = sourcePath,
        stagedTargetPath = stagedTargetPath,
        baseSourceHash = baseSourceHash,
        targetSourceHash = targetSourceHash
    )

    private companion object {
        const val CHAT_RECALL_CORPUS_KEY = "chat_recall_long_term"
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
        val VALID_INDEX_FINGERPRINT: String = "a".repeat(64)
        val ALL_JOB_STATUSES = listOf(
            MemoryMaintenanceJobStatus.PENDING,
            MemoryMaintenanceJobStatus.RUNNING,
            MemoryMaintenanceJobStatus.SUCCEEDED,
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            MemoryMaintenanceJobStatus.FAILED_TERMINAL,
            MemoryMaintenanceJobStatus.WAITING_REPAIR,
            MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY,
            MemoryMaintenanceJobStatus.DISMISSED
        )
    }
}
