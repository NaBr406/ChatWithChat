package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryModelDependencyNotifierTest {
    @Test
    fun `dependency change reopens matching work and schedules semantic processing`() = runBlocking {
        val reason = MemoryModelUnavailableReason.NO_ELIGIBLE_MODEL.code
        val job = MemoryMaintenanceJob(
            jobId = "blocked-memory-model",
            type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
            status = MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY,
            idempotencyKey = "blocked-memory-model",
            payloadJson = "{}",
            attempts = 1,
            lastError = reason,
            createdAt = 1,
            startedAt = null,
            updatedAt = 1,
            nextRunAt = null,
            family = MemoryMaintenanceJobFamily.SEMANTIC,
            retryCycle = 2,
            blockedReason = reason
        )
        val dao = InMemoryMaintenanceJobDao(listOf(job))
        val scheduler = MemoryMaintenanceScheduler(dao)
        val workEnqueuer = RecordingWorkEnqueuer()
        val notifier = SchedulerMemoryModelDependencyNotifier(
            schedulerProvider = Provider { scheduler },
            workEnqueuer = workEnqueuer
        )

        notifier.onDependenciesChanged()

        val reopened = checkNotNull(dao.getById(job.jobId))
        assertEquals(MemoryMaintenanceJobStatus.PENDING, reopened.status)
        assertEquals(0, reopened.attempts)
        assertEquals(3, reopened.retryCycle)
        assertEquals(listOf(MemoryMaintenanceJobFamily.SEMANTIC), workEnqueuer.works.map { work -> work.family })
    }

    @Test
    fun `dependency change without matching jobs does not schedule work`() = runBlocking {
        val workEnqueuer = RecordingWorkEnqueuer()
        val notifier = SchedulerMemoryModelDependencyNotifier(
            schedulerProvider = Provider { MemoryMaintenanceScheduler(InMemoryMaintenanceJobDao()) },
            workEnqueuer = workEnqueuer
        )

        notifier.onDependenciesChanged()

        assertEquals(0, workEnqueuer.enqueueCalls)
    }
}
