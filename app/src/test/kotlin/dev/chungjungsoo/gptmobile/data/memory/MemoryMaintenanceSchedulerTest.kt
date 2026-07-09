package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMaintenanceSchedulerTest {

    @Test
    fun `enqueue persists one job per idempotency key`() = runBlocking {
        val dao = InMemoryMemoryMaintenanceJobDao()
        val scheduler = createScheduler(dao)

        val first = scheduler.enqueue(
            type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
            idempotencyKey = "append:chat-1",
            payloadJson = """{"chatId":1}"""
        )
        val second = scheduler.enqueue(
            type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
            idempotencyKey = "append:chat-1",
            payloadJson = """{"chatId":1,"duplicate":true}"""
        )

        assertEquals(first.jobId, second.jobId)
        assertEquals(1, dao.jobs.size)
        assertEquals("""{"chatId":1}""", dao.jobs.single().payloadJson)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, dao.jobs.single().status)
    }

    @Test
    fun `mark running and succeeded updates attempts and terminal status`() = runBlocking {
        val dao = InMemoryMemoryMaintenanceJobDao()
        val scheduler = createScheduler(dao)
        val pending = scheduler.enqueue(
            type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
            idempotencyKey = "rebuild:memory",
            payloadJson = "{}"
        )

        val running = scheduler.markRunning(pending)
        val succeeded = scheduler.markSucceeded(running)

        assertNotEquals(pending.jobId, "")
        assertEquals(1, running.attempts)
        assertEquals(MemoryMaintenanceJobStatus.RUNNING, running.status)
        assertEquals(100L, running.startedAt)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, succeeded.status)
        assertNull(succeeded.lastError)
        assertEquals(succeeded, dao.jobs.single())
    }

    @Test
    fun `state transitions emit one status event after update`() = runBlocking {
        val dao = InMemoryMemoryMaintenanceJobDao()
        val eventSink = RecordingMemoryMaintenanceEventSink()
        val scheduler = createScheduler(dao, eventSink)
        val pending = scheduler.enqueue(
            type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
            idempotencyKey = "rebuild:memory",
            payloadJson = "{}"
        )

        val running = scheduler.markRunning(pending)

        assertEquals(1, eventSink.events.size)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, eventSink.events.single().oldStatus)
        assertEquals(MemoryMaintenanceJobStatus.RUNNING, eventSink.events.single().newStatus)
        assertEquals(running, eventSink.events.single().newJob)
        assertEquals(running, dao.jobs.single())
    }

    @Test
    fun `stale running jobs become retryable and runnable`() = runBlocking {
        val dao = InMemoryMemoryMaintenanceJobDao(
            initialJobs = listOf(
                MemoryMaintenanceJob(
                    jobId = "job-1",
                    type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    idempotencyKey = "append:chat-1",
                    payloadJson = "{}",
                    attempts = 1,
                    lastError = null,
                    createdAt = 1L,
                    startedAt = 1L,
                    updatedAt = 1L,
                    nextRunAt = null
                )
            )
        )
        val scheduler = createScheduler(dao)

        val resetCount = scheduler.resetStaleRunningJobs(olderThanSeconds = 60L)
        val runnable = scheduler.runnableJobs()

        assertEquals(1, resetCount)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, dao.jobs.single().status)
        assertTrue(dao.jobs.single().lastError.orEmpty().contains("interrupted"))
        assertEquals(listOf("job-1"), runnable.map { it.jobId })
    }

    @Test
    fun `next scheduled run uses earliest future runnable job`() = runBlocking {
        val dao = InMemoryMemoryMaintenanceJobDao(
            initialJobs = listOf(
                MemoryMaintenanceJob(
                    jobId = "job-later",
                    type = MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
                    status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
                    idempotencyKey = "append:later",
                    payloadJson = "{}",
                    attempts = 1,
                    lastError = "temporary",
                    createdAt = 1L,
                    startedAt = null,
                    updatedAt = 1L,
                    nextRunAt = 500L
                ),
                MemoryMaintenanceJob(
                    jobId = "job-earlier",
                    type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
                    status = MemoryMaintenanceJobStatus.PENDING,
                    idempotencyKey = "rebuild:earlier",
                    payloadJson = "{}",
                    attempts = 0,
                    lastError = null,
                    createdAt = 1L,
                    startedAt = null,
                    updatedAt = 1L,
                    nextRunAt = 140L
                )
            )
        )
        val scheduler = createScheduler(dao)

        assertEquals(140L, scheduler.nextScheduledRunAt())
        assertEquals(40L, scheduler.nextScheduledDelaySeconds())
    }

    private fun createScheduler(
        dao: InMemoryMemoryMaintenanceJobDao,
        eventSink: MemoryMaintenanceEventSink = MemoryMaintenanceEventSink.None
    ): MemoryMaintenanceScheduler = MemoryMaintenanceScheduler(
        jobDao = dao,
        clock = Clock.fixed(Instant.ofEpochSecond(100L), ZoneOffset.UTC),
        eventSink = eventSink
    )
}

private class RecordingMemoryMaintenanceEventSink : MemoryMaintenanceEventSink {
    val events = mutableListOf<MemoryMaintenanceStatusChangedEvent>()

    override suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent) {
        events += event
    }
}

private class InMemoryMemoryMaintenanceJobDao(
    initialJobs: List<MemoryMaintenanceJob> = emptyList()
) : MemoryMaintenanceJobDao {
    val jobs = initialJobs.toMutableList()

    override suspend fun getById(jobId: String): MemoryMaintenanceJob? =
        jobs.firstOrNull { it.jobId == jobId }

    override suspend fun getByIdempotencyKey(idempotencyKey: String): MemoryMaintenanceJob? =
        jobs.firstOrNull { it.idempotencyKey == idempotencyKey }

    override suspend fun getVisibleJobs(limit: Int): List<MemoryMaintenanceJob> =
        jobs.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun getRunnableJobs(
        statuses: List<String>,
        now: Long,
        limit: Int
    ): List<MemoryMaintenanceJob> = jobs
        .filter { job -> job.status in statuses }
        .filter { job -> now >= (job.nextRunAt ?: 0L) }
        .sortedBy { it.createdAt }
        .take(limit)

    override suspend fun getEarliestFutureRunAt(now: Long): Long? =
        jobs
            .filter { job -> job.status in listOf(MemoryMaintenanceJobStatus.PENDING, MemoryMaintenanceJobStatus.FAILED_RETRYABLE) }
            .mapNotNull { job -> job.nextRunAt }
            .filter { nextRunAt -> nextRunAt > now }
            .minOrNull()

    override suspend fun insertIgnore(job: MemoryMaintenanceJob): Long {
        if (jobs.any { it.idempotencyKey == job.idempotencyKey }) return -1L
        jobs += job
        return jobs.size.toLong()
    }

    override suspend fun update(job: MemoryMaintenanceJob) {
        val index = jobs.indexOfFirst { it.jobId == job.jobId }
        if (index != -1) {
            jobs[index] = job
        }
    }

    override suspend fun moveStaleJobs(
        fromStatus: String,
        status: String,
        before: Long,
        lastError: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int {
        var changedCount = 0
        jobs.replaceAll { job ->
            if (job.status == fromStatus && job.updatedAt < before) {
                changedCount += 1
                job.copy(
                    status = status,
                    lastError = lastError,
                    updatedAt = updatedAt,
                    nextRunAt = nextRunAt
                )
            } else {
                job
            }
        }
        return changedCount
    }
}
