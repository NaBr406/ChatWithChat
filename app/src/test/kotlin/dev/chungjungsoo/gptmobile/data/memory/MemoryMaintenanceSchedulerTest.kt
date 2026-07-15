package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMaintenanceSchedulerTest {

    @Test
    fun `enqueue persists explicit family and one job per idempotency key`() = runBlocking {
        val dao = InMemoryMaintenanceJobDao()
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
        assertEquals(MemoryMaintenanceJobFamily.SEMANTIC, first.family)
        assertEquals("""{"chatId":1}""", dao.jobs.single().payloadJson)
    }

    @Test
    fun `generation aware vector jobs are not suppressed by repeated content keys`() = runBlocking {
        val dao = InMemoryMaintenanceJobDao()
        val scheduler = createScheduler(dao)

        val generationOne = scheduler.enqueue(
            type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
            idempotencyKey = "sync:hash-a",
            payloadJson = "{}",
            generation = 1
        )
        val generationTwo = scheduler.enqueue(
            type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
            idempotencyKey = "sync:hash-b",
            payloadJson = "{}",
            generation = 2
        )
        val generationThree = scheduler.enqueue(
            type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
            idempotencyKey = "sync:hash-a",
            payloadJson = "{}",
            generation = 3
        )

        assertEquals(3, dao.jobs.size)
        assertEquals(listOf(1L, 2L, 3L), listOf(generationOne, generationTwo, generationThree).map { it.generation })
        assertTrue(generationOne.idempotencyKey != generationThree.idempotencyKey)
        assertEquals(MemoryMaintenanceJobFamily.INDEX, generationThree.family)
    }

    @Test
    fun `claim owns lease and blocks a second global semantic claim`() = runBlocking {
        val dao = InMemoryMaintenanceJobDao(
            listOf(
                job("semantic-1", MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH, createdAt = 1),
                job("semantic-2", MemoryMaintenanceJobType.DISTILL_DAILY_NOTES, createdAt = 2)
            )
        )
        val eventSink = RecordingMemoryMaintenanceEventSink()
        val scheduler = createScheduler(dao, eventSink)

        val claimed = scheduler.claimNextRunnable(MemoryMaintenanceJobFamily.SEMANTIC, "owner-1")
        val blocked = scheduler.claimNextRunnable(MemoryMaintenanceJobFamily.SEMANTIC, "owner-2")

        assertEquals("semantic-1", claimed?.jobId)
        assertEquals(MemoryMaintenanceJobStatus.RUNNING, claimed?.status)
        assertEquals(1, claimed?.attempts)
        assertEquals(0, claimed?.retryCycle)
        assertEquals(1L, claimed?.rowVersion)
        assertEquals("owner-1", claimed?.leaseOwner)
        assertEquals(1_900L, claimed?.leaseExpiresAt)
        assertNull(blocked)
        assertTrue(!scheduler.hasRunnableJob(MemoryMaintenanceJobFamily.SEMANTIC))
        assertEquals(1, eventSink.events.size)
        assertEquals(MemoryMaintenanceJobStatus.RUNNING, eventSink.events.single().newStatus)
    }

    @Test
    fun `stale lease owner cannot complete a replacement claim`() = runBlocking {
        val dao = InMemoryMaintenanceJobDao(listOf(job("index-1", MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX)))
        val scheduler = createScheduler(dao)
        val firstClaim = checkNotNull(
            scheduler.claimNextRunnable(MemoryMaintenanceJobFamily.INDEX, "old-owner")
        )
        dao.forceUpdate(
            firstClaim.copy(
                leaseOwner = "new-owner",
                rowVersion = firstClaim.rowVersion + 1
            )
        )

        assertThrows(MemoryMaintenanceLeaseLostException::class.java) {
            runBlocking { scheduler.markSucceeded(firstClaim) }
        }
        assertEquals(MemoryMaintenanceJobStatus.RUNNING, dao.jobs.single().status)
        assertEquals("new-owner", dao.jobs.single().leaseOwner)
    }

    @Test
    fun `repair reclaims expired and legacy leases but preserves active lease`() = runBlocking {
        val dao = InMemoryMaintenanceJobDao(
            listOf(
                job(
                    jobId = "legacy",
                    type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    attempts = 1,
                    leaseOwner = null,
                    leaseExpiresAt = null
                ),
                job(
                    jobId = "active",
                    type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    attempts = 1,
                    leaseOwner = "active-owner",
                    leaseExpiresAt = 200
                )
            )
        )
        val scheduler = createScheduler(dao)

        val resetCount = scheduler.resetExpiredRunningJobs(now = 100)

        assertEquals(1, resetCount)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, dao.getById("legacy")?.status)
        assertEquals(100L, dao.getById("legacy")?.nextRunAt)
        assertEquals(MemoryMaintenanceJobStatus.RUNNING, dao.getById("active")?.status)
    }

    @Test
    fun `expired leases honor semantic and local exhaustion policies`() = runBlocking {
        val dao = InMemoryMaintenanceJobDao(
            listOf(
                job(
                    jobId = "semantic-exhausted",
                    type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    attempts = 3,
                    leaseOwner = "semantic-owner",
                    leaseExpiresAt = 90
                ),
                job(
                    jobId = "index-exhausted",
                    type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    attempts = 5,
                    leaseOwner = "index-owner",
                    leaseExpiresAt = 90
                )
            )
        )

        val resetCount = createScheduler(dao).resetExpiredRunningJobs(now = 100)

        assertEquals(2, resetCount)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, dao.getById("semantic-exhausted")?.status)
        assertEquals(MemoryMaintenanceJobStatus.WAITING_REPAIR, dao.getById("index-exhausted")?.status)
        assertNull(dao.getById("semantic-exhausted")?.nextRunAt)
        assertNull(dao.getById("index-exhausted")?.nextRunAt)
    }

    @Test
    fun `expired and legacy recovered sources transition without intermediate lease failure events`() = runBlocking {
        val terminalReason = MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING
        listOf(
            Triple("dead-owner-0", 90L, null),
            Triple("dead-owner-1", null, null),
            Triple(null, 150L, null),
            Triple("dead-owner-3", 90L, terminalReason)
        ).forEachIndexed { index, (leaseOwner, leaseExpiresAt, reason) ->
            val sourceJob = job(
                jobId = "expired-recovered-$index",
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                status = MemoryMaintenanceJobStatus.RUNNING,
                attempts = 3,
                leaseOwner = leaseOwner,
                leaseExpiresAt = leaseExpiresAt
            )
            val dao = InMemoryMaintenanceJobDao(listOf(sourceJob))
            val eventSink = RecordingMemoryMaintenanceEventSink()
            val scheduler = createScheduler(dao, eventSink)

            val disposition = if (reason == null) {
                scheduler.markRecoveredSucceeded(sourceJob.jobId)
            } else {
                scheduler.markRecoveredConflict(sourceJob.jobId, reason)
            }

            assertEquals(MemoryRecoveredJobDisposition.SUCCEEDED, disposition)
            val recovered = checkNotNull(dao.getById(sourceJob.jobId))
            assertEquals(
                if (reason == null) MemoryMaintenanceJobStatus.SUCCEEDED else MemoryMaintenanceJobStatus.FAILED_TERMINAL,
                recovered.status
            )
            assertEquals(reason, recovered.lastError)
            assertEquals(reason, recovered.blockedReason)
            assertNull(recovered.startedAt)
            assertNull(recovered.nextRunAt)
            assertNull(recovered.leaseOwner)
            assertNull(recovered.leaseExpiresAt)
            assertEquals(sourceJob.rowVersion + 1, recovered.rowVersion)
            assertEquals(1, eventSink.events.size)
            assertEquals(MemoryMaintenanceJobStatus.RUNNING, eventSink.events.single().oldStatus)
            assertEquals(recovered.status, eventSink.events.single().newStatus)
            assertTrue(
                eventSink.events.none { event ->
                    event.newStatus == MemoryMaintenanceJobStatus.FAILED_RETRYABLE ||
                        event.newStatus == MemoryMaintenanceJobStatus.WAITING_REPAIR
                }
            )
            assertEquals(0, scheduler.resetExpiredRunningJobs())
        }
    }

    @Test
    fun `recovered conflict rewrites stale terminal dispositions exactly once`() = runBlocking {
        val exactReason = MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING
        val staleJobs = listOf(
            job(
                jobId = "already-terminal",
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                status = MemoryMaintenanceJobStatus.FAILED_TERMINAL
            ).copy(lastError = "old_generic_error", blockedReason = "old_generic_error"),
            job(
                jobId = "already-succeeded",
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                status = MemoryMaintenanceJobStatus.SUCCEEDED
            )
        )

        staleJobs.forEach { staleJob ->
            val dao = InMemoryMaintenanceJobDao(listOf(staleJob))
            val eventSink = RecordingMemoryMaintenanceEventSink()
            val scheduler = createScheduler(dao, eventSink)

            assertEquals(
                MemoryRecoveredJobDisposition.SUCCEEDED,
                scheduler.markRecoveredConflict(staleJob.jobId, exactReason)
            )
            val rewritten = checkNotNull(dao.getById(staleJob.jobId))
            assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, rewritten.status)
            assertEquals(exactReason, rewritten.lastError)
            assertEquals(exactReason, rewritten.blockedReason)
            assertEquals(staleJob.attempts, rewritten.attempts)
            assertEquals(staleJob.rowVersion + 1, rewritten.rowVersion)
            assertEquals(1, eventSink.events.size)
            val notification = MemoryMaintenanceNotificationPolicy().decide(
                event = eventSink.events.single(),
                preferenceEnabled = true,
                systemPermissionGranted = true
            ) as MemoryMaintenanceNotificationDecision.ShowFailed
            assertTrue(notification.terminal)
            assertFalse(notification.allowRetry)

            assertEquals(
                MemoryRecoveredJobDisposition.SUCCEEDED,
                scheduler.markRecoveredConflict(staleJob.jobId, exactReason)
            )
            assertEquals(rewritten, dao.getById(staleJob.jobId))
            assertEquals(1, eventSink.events.size)
        }
    }

    @Test
    fun `startup reopens local waiting and legacy terminal jobs only`() = runBlocking {
        val jobs = listOf(
            job(
                jobId = "waiting-index",
                type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
                status = MemoryMaintenanceJobStatus.WAITING_REPAIR,
                attempts = 5,
                retryCycle = 1
            ),
            job(
                jobId = "legacy-index-terminal",
                type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
                status = MemoryMaintenanceJobStatus.FAILED_TERMINAL,
                attempts = 3
            ),
            job(
                jobId = "semantic-terminal",
                type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                status = MemoryMaintenanceJobStatus.FAILED_TERMINAL,
                attempts = 3
            ),
            job(
                jobId = "blocked-index",
                type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
                status = MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY,
                generation = 1
            )
        )
        val dao = InMemoryMaintenanceJobDao(jobs)

        val reopened = createScheduler(dao).reopenWaitingRepairJobs()

        assertEquals(2, reopened)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, dao.getById("waiting-index")?.status)
        assertEquals(2, dao.getById("waiting-index")?.retryCycle)
        assertEquals(MemoryMaintenanceJobStatus.PENDING, dao.getById("legacy-index-terminal")?.status)
        assertEquals(1, dao.getById("legacy-index-terminal")?.retryCycle)
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, dao.getById("semantic-terminal")?.status)
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, dao.getById("blocked-index")?.status)
    }

    @Test
    fun `semantic exhausts at three attempts while local work waits after five`() = runBlocking {
        val semanticDao = InMemoryMaintenanceJobDao(
            listOf(job("semantic", MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH))
        )
        val semanticScheduler = createScheduler(semanticDao)
        repeat(3) {
            val claimed = checkNotNull(
                semanticScheduler.claimNextRunnable(MemoryMaintenanceJobFamily.SEMANTIC, "semantic-owner")
            )
            val failed = semanticScheduler.markFailedRetryable(claimed, "temporary")
            if (failed.status == MemoryMaintenanceJobStatus.FAILED_RETRYABLE) {
                semanticDao.forceUpdate(failed.copy(nextRunAt = 100))
            }
        }
        assertEquals(MemoryMaintenanceJobStatus.FAILED_TERMINAL, semanticDao.jobs.single().status)
        assertEquals(3, semanticDao.jobs.single().attempts)

        val indexDao = InMemoryMaintenanceJobDao(
            listOf(job("index", MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX))
        )
        val indexScheduler = createScheduler(indexDao)
        repeat(5) {
            val claimed = checkNotNull(
                indexScheduler.claimNextRunnable(MemoryMaintenanceJobFamily.INDEX, "index-owner")
            )
            val failed = indexScheduler.markFailedRetryable(claimed, "temporary")
            if (failed.status == MemoryMaintenanceJobStatus.FAILED_RETRYABLE) {
                indexDao.forceUpdate(failed.copy(nextRunAt = 100))
            }
        }
        assertEquals(MemoryMaintenanceJobStatus.WAITING_REPAIR, indexDao.jobs.single().status)
        assertEquals(5, indexDao.jobs.single().attempts)
    }

    @Test
    fun `manual retry opens a new cycle but dependency block rejects retry`() = runBlocking {
        val waiting = job(
            jobId = "waiting",
            type = MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
            status = MemoryMaintenanceJobStatus.WAITING_REPAIR,
            attempts = 5,
            retryCycle = 2,
            blockedReason = "index failed"
        )
        val blocked = job(
            jobId = "blocked",
            type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
            status = MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY,
            attempts = 1,
            generation = 1,
            blockedReason = "model unavailable"
        )
        val dao = InMemoryMaintenanceJobDao(listOf(waiting, blocked))
        val scheduler = createScheduler(dao)

        val retried = scheduler.retryManually("waiting")

        assertEquals(MemoryMaintenanceJobStatus.PENDING, retried?.status)
        assertEquals(0, retried?.attempts)
        assertEquals(3, retried?.retryCycle)
        assertNull(retried?.blockedReason)
        assertEquals(100L, retried?.nextRunAt)
        assertNull(scheduler.retryManually("blocked"))
        assertEquals(MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY, dao.getById("blocked")?.status)
    }

    @Test
    fun `next scheduled run is scoped to family`() = runBlocking {
        val dao = InMemoryMaintenanceJobDao(
            listOf(
                job("semantic", MemoryMaintenanceJobType.APPEND_DAILY_NOTE, nextRunAt = 180),
                job("index", MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX, nextRunAt = 140)
            )
        )
        val scheduler = createScheduler(dao)

        assertEquals(180L, scheduler.nextScheduledRunAt(MemoryMaintenanceJobFamily.SEMANTIC))
        assertEquals(80L, scheduler.nextScheduledDelaySeconds(MemoryMaintenanceJobFamily.SEMANTIC))
        assertEquals(140L, scheduler.nextScheduledRunAt(MemoryMaintenanceJobFamily.INDEX))
        assertEquals(40L, scheduler.nextScheduledDelaySeconds(MemoryMaintenanceJobFamily.INDEX))
    }

    @Test
    fun `repair watchdog uses the earliest lease or repair retry`() = runBlocking {
        val dao = InMemoryMaintenanceJobDao(
            listOf(
                job(
                    jobId = "running-semantic",
                    type = MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH,
                    status = MemoryMaintenanceJobStatus.RUNNING,
                    leaseOwner = "owner",
                    leaseExpiresAt = 150
                ),
                job(
                    jobId = "repair-retry",
                    type = MemoryMaintenanceJobType.REPAIR_MARKDOWN_METADATA,
                    status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
                    nextRunAt = 180
                )
            )
        )
        val scheduler = createScheduler(dao)

        assertEquals(150L, scheduler.nextRepairWakeAt())
        assertEquals(50L, scheduler.nextRepairDelaySeconds())
    }

    @Test
    fun `heartbeat extends one claim generation and keeps it out of expired repair`() = runBlocking {
        val clock = MutableMemoryMaintenanceClock(1_000L)
        val dao = InMemoryMaintenanceJobDao(
            listOf(job("semantic-heartbeat", MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH))
        )
        val scheduler = MemoryMaintenanceScheduler(jobDao = dao, clock = clock)
        val claimed = checkNotNull(
            scheduler.claimNextRunnable(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "heartbeat-owner"
            )
        )
        val originalLeaseExpiry = checkNotNull(claimed.leaseExpiresAt)

        runWithMemoryMaintenanceLeaseHeartbeat(
            job = claimed,
            maintenanceScheduler = scheduler,
            heartbeatIntervalMillis = 1L
        ) {
            clock.setEpochSecond(originalLeaseExpiry - 100L)
            withTimeout(1_000L) {
                while (checkNotNull(dao.getById(claimed.jobId)).leaseExpiresAt == originalLeaseExpiry) {
                    yield()
                }
            }
            clock.setEpochSecond(originalLeaseExpiry + 1L)
            assertEquals(0, scheduler.resetExpiredRunningJobs())
        }

        val renewed = checkNotNull(dao.getById(claimed.jobId))
        assertEquals(claimed.rowVersion, renewed.rowVersion)
        assertTrue(checkNotNull(renewed.leaseExpiresAt) > originalLeaseExpiry)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, scheduler.markSucceeded(claimed).status)
    }

    private fun job(
        jobId: String,
        type: String,
        status: String = MemoryMaintenanceJobStatus.PENDING,
        attempts: Int = 0,
        createdAt: Long = 1,
        nextRunAt: Long? = null,
        generation: Long = 0,
        retryCycle: Int = 0,
        leaseOwner: String? = null,
        leaseExpiresAt: Long? = null,
        blockedReason: String? = null
    ): MemoryMaintenanceJob = MemoryMaintenanceJob(
        jobId = jobId,
        type = type,
        status = status,
        idempotencyKey = "key:$jobId",
        payloadJson = "{}",
        attempts = attempts,
        lastError = null,
        createdAt = createdAt,
        startedAt = createdAt.takeIf { status == MemoryMaintenanceJobStatus.RUNNING },
        updatedAt = createdAt,
        nextRunAt = nextRunAt,
        family = MemoryMaintenanceJobFamily.forType(type),
        generation = generation,
        retryCycle = retryCycle,
        leaseOwner = leaseOwner,
        leaseExpiresAt = leaseExpiresAt,
        blockedReason = blockedReason
    )

    private fun createScheduler(
        dao: InMemoryMaintenanceJobDao,
        eventSink: MemoryMaintenanceEventSink = MemoryMaintenanceEventSink.None
    ): MemoryMaintenanceScheduler = MemoryMaintenanceScheduler(
        jobDao = dao,
        clock = FIXED_CLOCK,
        eventSink = eventSink
    )

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(100L), ZoneOffset.UTC)
    }
}

private class MutableMemoryMaintenanceClock(epochSecond: Long) : Clock() {
    private var currentInstant: Instant = Instant.ofEpochSecond(epochSecond)

    fun setEpochSecond(epochSecond: Long) {
        currentInstant = Instant.ofEpochSecond(epochSecond)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(currentInstant, zone)

    override fun instant(): Instant = currentInstant
}

private class RecordingMemoryMaintenanceEventSink : MemoryMaintenanceEventSink {
    val events = mutableListOf<MemoryMaintenanceStatusChangedEvent>()

    override suspend fun onStatusChanged(event: MemoryMaintenanceStatusChangedEvent) {
        events += event
    }
}
