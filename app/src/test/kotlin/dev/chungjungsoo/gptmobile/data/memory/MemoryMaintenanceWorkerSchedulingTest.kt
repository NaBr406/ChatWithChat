package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryMaintenanceWorkerSchedulingTest {

    @Test
    fun `persisted retry schedules delayed wake and ready backlog successor`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                MemoryMaintenanceJob(
                    jobId = "semantic-job",
                    type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                    status = MemoryMaintenanceJobStatus.PENDING,
                    idempotencyKey = "semantic-key",
                    payloadJson = "{}",
                    attempts = 0,
                    lastError = null,
                    createdAt = 1L,
                    startedAt = null,
                    updatedAt = 1L,
                    nextRunAt = null,
                    family = MemoryMaintenanceJobFamily.SEMANTIC
                ),
                MemoryMaintenanceJob(
                    jobId = "semantic-job-2",
                    type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                    status = MemoryMaintenanceJobStatus.PENDING,
                    idempotencyKey = "semantic-key-2",
                    payloadJson = "{}",
                    attempts = 0,
                    lastError = null,
                    createdAt = 2L,
                    startedAt = null,
                    updatedAt = 2L,
                    nextRunAt = null,
                    family = MemoryMaintenanceJobFamily.SEMANTIC
                )
            )
        )
        val scheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val processor = MemoryMaintenanceProcessor(
            maintenanceScheduler = scheduler,
            settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true),
            leaseWatchdog = NoOpWorkerLeaseWatchdog
        )
        val enqueuer = RecordingWorkEnqueuer()
        val entryPoint = SchedulingEntryPoint(processor, scheduler, enqueuer)

        entryPoint.processAndReschedule(MemoryMaintenanceJobFamily.SEMANTIC)

        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, jobDao.getById("semantic-job")?.status)
        assertEquals(1_030L, jobDao.getById("semantic-job")?.nextRunAt)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, jobDao.getById("semantic-job-2")?.status)
        assertEquals(
            listOf(
                EnqueuedMemoryWork(MemoryMaintenanceJobFamily.SEMANTIC, 0L),
                EnqueuedMemoryWork(MemoryMaintenanceJobFamily.SEMANTIC, 30L)
            ),
            enqueuer.works
        )
    }

    @Test
    fun `reschedule failure does not turn a persisted retry into worker retry state`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                MemoryMaintenanceJob(
                    jobId = "semantic-job",
                    type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                    status = MemoryMaintenanceJobStatus.PENDING,
                    idempotencyKey = "semantic-key",
                    payloadJson = "{}",
                    attempts = 0,
                    lastError = null,
                    createdAt = 1L,
                    startedAt = null,
                    updatedAt = 1L,
                    nextRunAt = null,
                    family = MemoryMaintenanceJobFamily.SEMANTIC
                )
            )
        )
        val scheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val processor = MemoryMaintenanceProcessor(
            maintenanceScheduler = scheduler,
            settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true),
            leaseWatchdog = NoOpWorkerLeaseWatchdog
        )
        val entryPoint = SchedulingEntryPoint(processor, scheduler, ThrowingWorkEnqueuer)

        val disposition = executeMemoryMaintenanceWorkerInvocation(
            entryPointProvider = { entryPoint }
        ) {
            processAndReschedule(MemoryMaintenanceJobFamily.SEMANTIC)
        }

        assertEquals(MemoryMaintenanceWorkerDisposition.SUCCESS, disposition)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, jobDao.jobs.single().status)
        assertEquals(1_030L, jobDao.jobs.single().nextRunAt)
    }

    @Test
    fun `zero processed and no follow up wake maps scheduling failure to retry`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao()
        val scheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val processor = MemoryMaintenanceProcessor(
            maintenanceScheduler = scheduler,
            settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true),
            leaseWatchdog = NoOpWorkerLeaseWatchdog
        )
        val entryPoint = SchedulingEntryPoint(processor, scheduler, ThrowingWorkEnqueuer)

        val disposition = executeMemoryMaintenanceWorkerInvocation(
            entryPointProvider = { entryPoint }
        ) {
            processAndReschedule(MemoryMaintenanceJobFamily.SEMANTIC)
        }

        assertEquals(MemoryMaintenanceWorkerDisposition.RETRY, disposition)
    }

    @Test
    fun `worker invocation distinguishes permanent contracts lease loss and cancellation`() {
        assertEquals(
            MemoryMaintenanceWorkerDisposition.FAILURE,
            runBlocking {
                executeMemoryMaintenanceWorkerInvocation(
                    entryPointProvider = { error("Hilt entry point contract is invalid") },
                    block = {}
                )
            }
        )
        assertEquals(
            MemoryMaintenanceWorkerDisposition.FAILURE,
            runBlocking {
                executeMemoryMaintenanceWorkerInvocation(
                    entryPointProvider = { UnusedWorkerEntryPoint },
                    block = { throw IllegalArgumentException("invalid worker input") }
                )
            }
        )
        assertEquals(
            MemoryMaintenanceWorkerDisposition.SUCCESS,
            runBlocking {
                executeMemoryMaintenanceWorkerInvocation(
                    entryPointProvider = { UnusedWorkerEntryPoint },
                    block = { throw MemoryMaintenanceLeaseLostException("stale-job") }
                )
            }
        )
        assertThrows(CancellationException::class.java) {
            runBlocking {
                executeMemoryMaintenanceWorkerInvocation(
                    entryPointProvider = { UnusedWorkerEntryPoint },
                    block = { throw CancellationException("worker stopped") }
                )
            }
        }
    }

    @Test
    fun `active semantic lease schedules watchdog without immediate successor loop`() = runBlocking {
        val jobDao = InMemoryMaintenanceJobDao(
            listOf(
                MemoryMaintenanceJob(
                    jobId = "running-semantic",
                    type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    idempotencyKey = "running-key",
                    payloadJson = "{}",
                    attempts = 1,
                    lastError = null,
                    createdAt = 1L,
                    startedAt = 1_000L,
                    updatedAt = 1_000L,
                    nextRunAt = null,
                    family = MemoryMaintenanceJobFamily.SEMANTIC,
                    rowVersion = 1,
                    leaseOwner = "other-worker",
                    leaseExpiresAt = 1_100L
                ),
                MemoryMaintenanceJob(
                    jobId = "pending-semantic",
                    type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                    status = MemoryMaintenanceJobStatus.PENDING,
                    idempotencyKey = "pending-key",
                    payloadJson = "{}",
                    attempts = 0,
                    lastError = null,
                    createdAt = 2L,
                    startedAt = null,
                    updatedAt = 2L,
                    nextRunAt = null,
                    family = MemoryMaintenanceJobFamily.SEMANTIC
                )
            )
        )
        val scheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val processor = MemoryMaintenanceProcessor(
            maintenanceScheduler = scheduler,
            settingRepository = FakeMaintenanceSettingRepository(memoryEnabled = true),
            leaseWatchdog = NoOpWorkerLeaseWatchdog
        )
        val enqueuer = RecordingWorkEnqueuer()

        SchedulingEntryPoint(processor, scheduler, enqueuer)
            .processAndReschedule(MemoryMaintenanceJobFamily.SEMANTIC)

        assertEquals(MemoryMaintenanceJobStatus.PENDING, jobDao.getById("pending-semantic")?.status)
        assertEquals(
            listOf(EnqueuedMemoryWork(MemoryMaintenanceJobFamily.REPAIR, 100L)),
            enqueuer.works
        )
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
    }
}

private data object NoOpWorkerLeaseWatchdog : MemoryMaintenanceLeaseWatchdog {
    override suspend fun scheduleLeaseWatchdog() = Unit
}

private data object ThrowingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
    override fun enqueueWork(family: String, delaySeconds: Long) {
        error("work manager unavailable")
    }
}

private data object UnusedWorkerEntryPoint : MemoryMaintenanceWorkerEntryPoint {
    override fun memoryMaintenanceProcessor(): MemoryMaintenanceProcessor = error("Not used")
    override fun memoryMaintenanceRepairer(): MemoryMaintenanceRepairer = error("Not used")
    override fun memoryMaintenanceScheduler(): MemoryMaintenanceScheduler = error("Not used")
    override fun memoryMaintenanceWorkEnqueuer(): MemoryMaintenanceWorkEnqueuer = error("Not used")
    override fun memoryTurnBatchScheduler(): MemoryTurnBatchScheduler = error("Not used")
}

private class SchedulingEntryPoint(
    private val processor: MemoryMaintenanceProcessor,
    private val scheduler: MemoryMaintenanceScheduler,
    private val enqueuer: MemoryMaintenanceWorkEnqueuer
) : MemoryMaintenanceWorkerEntryPoint {
    override fun memoryMaintenanceProcessor(): MemoryMaintenanceProcessor = processor
    override fun memoryMaintenanceScheduler(): MemoryMaintenanceScheduler = scheduler
    override fun memoryMaintenanceWorkEnqueuer(): MemoryMaintenanceWorkEnqueuer = enqueuer
    override fun memoryMaintenanceRepairer(): MemoryMaintenanceRepairer = error("Not used")
    override fun memoryTurnBatchScheduler(): MemoryTurnBatchScheduler = error("Not used")
}
