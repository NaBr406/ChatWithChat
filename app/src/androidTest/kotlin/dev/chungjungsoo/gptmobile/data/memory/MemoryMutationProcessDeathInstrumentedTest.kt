package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryMutationProcessDeathInstrumentedTest {
    @Test
    fun phase1_prepareBothCrashWindowsAndKillProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetHarnessState(context)
        val fixture = HarnessFixture(context)

        fixture.fileStore.ensureStore().getOrThrow()
        fixture.fileStore.appendDailyNote(DAILY_BASE_NOTE, FIXED_DATE).getOrThrow()
        val dailyBaseContent = fixture.fileStore.readDailyMemory(FIXED_DATE).getOrThrow()
        val longTermBaseContent = fixture.fileStore.readLongTermMemory().getOrThrow()

        fixture.enqueueSemanticJob(PREPARED_JOB_ID)
        val preparedDailyMutation = fixture.coordinator.prepare(
            semanticJobId = PREPARED_JOB_ID,
            semanticBatchId = PREPARED_BATCH_ID,
            targets = listOf(
                MemoryMutationTarget(
                    sourcePath = DAILY_SOURCE_PATH,
                    baseContent = dailyBaseContent,
                    targetContent = DAILY_TARGET_CONTENT,
                    targetIndexFingerprint = null
                )
            )
        )

        fixture.enqueueSemanticJob(FILE_COMMITTED_JOB_ID)
        val fileCommittedMutation = fixture.coordinator.prepare(
            semanticJobId = FILE_COMMITTED_JOB_ID,
            semanticBatchId = FILE_COMMITTED_BATCH_ID,
            targets = listOf(
                MemoryMutationTarget(
                    sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                    baseContent = longTermBaseContent,
                    targetContent = LONG_TERM_TARGET_CONTENT,
                    targetIndexFingerprint = TARGET_INDEX_FINGERPRINT
                )
            )
        )
        val longTermReceipt = fileCommittedMutation.receipts.single()
        val commitOutcome = fixture.fileStore.commitStagedMemoryFile(
            sourcePath = longTermReceipt.sourcePath,
            stagedTargetPath = longTermReceipt.stagedTargetPath,
            baseSourceHash = longTermReceipt.baseSourceHash,
            targetSourceHash = longTermReceipt.targetSourceHash
        ).getOrThrow()

        assertEquals(MemoryMutationState.PREPARED, preparedDailyMutation.group.state)
        assertEquals(MemoryMutationState.PREPARED, preparedDailyMutation.receipts.single().state)
        assertTrue(commitOutcome is MemoryFileCommitOutcome.Committed)
        assertEquals(
            MemoryMutationState.PREPARED,
            fixture.database.memoryRecoveryDao().getMutationReceipt(longTermReceipt.receiptId)?.state
        )
        assertEquals(
            longTermReceipt.targetSourceHash,
            fixture.fileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrThrow()
        )

        fixture.close()
        writeDurablePhaseOneMarker(context)
        Process.killProcess(Process.myPid())
        Thread.sleep(PROCESS_DEATH_TIMEOUT_MILLIS)
        fail("Process survived the process-death failpoint")
    }

    @Test
    fun phase2_recoverBothCrashWindowsWithoutSemanticReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Phase-one marker is missing", markerFile(context).isFile)
        val fixture = HarnessFixture(context)
        val recoveryDao = fixture.database.memoryRecoveryDao()

        val preparedGroup = recoveryDao.getMutationGroupBySemanticJobId(PREPARED_JOB_ID)
        val fileCommittedGroup = recoveryDao.getMutationGroupBySemanticJobId(FILE_COMMITTED_JOB_ID)
        assertNotNull(preparedGroup)
        assertNotNull(fileCommittedGroup)
        val preparedReceipt = recoveryDao.getMutationReceipts(checkNotNull(preparedGroup).groupId).single()
        val fileCommittedReceipt = recoveryDao.getMutationReceipts(checkNotNull(fileCommittedGroup).groupId).single()

        assertEquals(MemoryMutationState.PREPARED, preparedReceipt.state)
        assertEquals(preparedReceipt.baseSourceHash, fixture.fileStore.currentMemoryFileHash(DAILY_SOURCE_PATH).getOrThrow())
        assertEquals(MemoryMutationState.PREPARED, fileCommittedReceipt.state)
        assertEquals(
            fileCommittedReceipt.targetSourceHash,
            fixture.fileStore.currentMemoryFileHash(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).getOrThrow()
        )

        val firstRecovery = fixture.recoveryService.recoverIncomplete(scheduleRetry = false)

        assertEquals(2, firstRecovery.committedCount)
        assertEquals(0, firstRecovery.conflictCount)
        assertEquals(0, firstRecovery.failedCount)
        assertEquals(2, firstRecovery.recoveredSemanticCount)
        assertEquals(normalized(DAILY_TARGET_CONTENT), fixture.fileStore.readDailyMemory(FIXED_DATE).getOrThrow())
        assertEquals(normalized(LONG_TERM_TARGET_CONTENT), fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(
            MemoryMutationState.INDEXED,
            recoveryDao.getMutationReceipt(preparedReceipt.receiptId)?.state
        )
        assertEquals(
            MemoryMutationState.INDEX_PENDING,
            recoveryDao.getMutationReceipt(fileCommittedReceipt.receiptId)?.state
        )
        assertEquals(
            MemoryMutationState.INDEXED,
            recoveryDao.getMutationGroup(checkNotNull(preparedGroup).groupId)?.state
        )
        assertEquals(
            MemoryMutationState.INDEX_PENDING,
            recoveryDao.getMutationGroup(checkNotNull(fileCommittedGroup).groupId)?.state
        )
        assertEquals(
            MemoryMaintenanceJobStatus.SUCCEEDED,
            fixture.database.memoryMaintenanceJobDao().getById(PREPARED_JOB_ID)?.status
        )
        assertEquals(
            MemoryMaintenanceJobStatus.SUCCEEDED,
            fixture.database.memoryMaintenanceJobDao().getById(FILE_COMMITTED_JOB_ID)?.status
        )

        val syncJobsAfterFirstRecovery = fixture.pendingIndexSyncJobs()
        assertEquals(1, syncJobsAfterFirstRecovery.size)
        assertEquals(checkNotNull(fileCommittedGroup).generation, syncJobsAfterFirstRecovery.single().generation)
        assertEquals(
            fileCommittedReceipt.targetSourceHash,
            recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)?.sourceHash
        )

        val secondRecovery = fixture.recoveryService.recoverIncomplete(scheduleRetry = false)

        assertEquals(0, secondRecovery.failedCount)
        assertEquals(0, secondRecovery.recoveredSemanticCount)
        assertEquals(normalized(DAILY_TARGET_CONTENT), fixture.fileStore.readDailyMemory(FIXED_DATE).getOrThrow())
        assertEquals(normalized(LONG_TERM_TARGET_CONTENT), fixture.fileStore.readLongTermMemory().getOrThrow())
        assertEquals(1, fixture.pendingIndexSyncJobs().size)

        fixture.close()
        resetHarnessState(context)
    }

    private fun resetHarnessState(context: Context) {
        context.deleteDatabase(DATABASE_NAME)
        harnessRoot(context).deleteRecursively()
    }

    private fun writeDurablePhaseOneMarker(context: Context) {
        val marker = markerFile(context)
        marker.parentFile?.mkdirs()
        FileOutputStream(marker).use { output ->
            output.write("ready\n".toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun markerFile(context: Context): File = File(harnessRoot(context), PHASE_ONE_MARKER_NAME)

    private fun harnessRoot(context: Context): File = File(context.filesDir, HARNESS_ROOT_NAME)

    private fun normalized(content: String): String = content.trimEnd() + "\n"

    private class HarnessFixture(context: Context) {
        val database: ChatDatabaseV2 = Room.databaseBuilder(context, ChatDatabaseV2::class.java, DATABASE_NAME).build()
        val fileStore = MemoryFileStore(MemoryFilePaths(harnessRoot(context)), FIXED_CLOCK)
        private val maintenanceScheduler = MemoryMaintenanceScheduler(database.memoryMaintenanceJobDao(), FIXED_CLOCK)
        val coordinator = MemoryMutationCoordinator(
            recoveryDao = database.memoryRecoveryDao(),
            memoryFileStore = fileStore,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = NoOpWorkEnqueuer,
            clock = FIXED_CLOCK
        )
        val recoveryService = MemoryMutationRecoveryService(
            memoryMutationCoordinator = coordinator,
            turnBatchDao = database.memoryTurnBatchDao(),
            maintenanceScheduler = maintenanceScheduler,
            clock = FIXED_CLOCK
        )

        suspend fun enqueueSemanticJob(jobId: String) {
            maintenanceScheduler.enqueue(
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                idempotencyKey = jobId,
                payloadJson = "{}",
                jobId = jobId
            )
        }

        suspend fun pendingIndexSyncJobs() = database.memoryMaintenanceJobDao().getByTypeAndStatuses(
            type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
            statuses = listOf(MemoryMaintenanceJobStatus.PENDING)
        )

        fun close() {
            database.close()
        }
    }

    private object NoOpWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
        override fun enqueueWork(family: String, delaySeconds: Long) = Unit
    }

    private companion object {
        const val DATABASE_NAME = "memory-process-death-harness-v1.db"
        const val HARNESS_ROOT_NAME = "memory_process_death_harness_v1"
        const val PHASE_ONE_MARKER_NAME = "phase-one-ready"
        const val PREPARED_JOB_ID = "process-death-prepared-job"
        const val PREPARED_BATCH_ID = "process-death-prepared-batch"
        const val FILE_COMMITTED_JOB_ID = "process-death-file-committed-job"
        const val FILE_COMMITTED_BATCH_ID = "process-death-file-committed-batch"
        const val DAILY_BASE_NOTE = "Daily base remains visible until receipt recovery."
        const val DAILY_TARGET_CONTENT = "# 2026-07-12\n\nDaily target committed by recovery."
        const val LONG_TERM_TARGET_CONTENT = "# ChatWithChat Memory\n\nLong-term target already renamed before recovery."
        const val TARGET_INDEX_FINGERPRINT = "process-death-harness-fingerprint-v1"
        const val PROCESS_DEATH_TIMEOUT_MILLIS = 30_000L
        val FIXED_DATE: LocalDate = LocalDate.parse("2026-07-12")
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC)
        val DAILY_SOURCE_PATH = "${MemoryFilePaths.DAILY_MEMORY_DIRECTORY_NAME}/2026-07-12.md"
        val CHAT_RECALL_CORPUS_KEY: String = MemoryCorpus.CHAT_RECALL_LONG_TERM.name.lowercase()

        fun harnessRoot(context: Context): File = File(context.filesDir, HARNESS_ROOT_NAME)
    }
}
