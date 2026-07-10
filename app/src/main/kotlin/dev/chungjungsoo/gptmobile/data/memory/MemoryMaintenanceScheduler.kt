package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import java.time.Clock
import java.util.UUID

class MemoryMaintenanceScheduler(
    private val jobDao: MemoryMaintenanceJobDao,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val eventSink: MemoryMaintenanceEventSink = MemoryMaintenanceEventSink.None
) {

    suspend fun enqueue(
        type: String,
        idempotencyKey: String,
        payloadJson: String,
        nextRunAt: Long? = null,
        jobId: String = UUID.randomUUID().toString()
    ): MemoryMaintenanceJob {
        val existing = jobDao.getByIdempotencyKey(idempotencyKey)
        if (existing != null) return existing

        val now = now()
        val job = MemoryMaintenanceJob(
            jobId = jobId,
            type = type,
            status = MemoryMaintenanceJobStatus.PENDING,
            idempotencyKey = idempotencyKey,
            payloadJson = payloadJson,
            attempts = 0,
            lastError = null,
            createdAt = now,
            startedAt = null,
            updatedAt = now,
            nextRunAt = nextRunAt
        )
        val inserted = jobDao.insertIgnore(job)
        if (inserted != -1L) return job

        return jobDao.getByIdempotencyKey(idempotencyKey) ?: job
    }

    suspend fun runnableJobs(
        limit: Int = DEFAULT_RUNNABLE_LIMIT,
        now: Long = now()
    ): List<MemoryMaintenanceJob> {
        val candidates = jobDao.getRunnableJobs(
            statuses = listOf(MemoryMaintenanceJobStatus.PENDING, MemoryMaintenanceJobStatus.FAILED_RETRYABLE),
            now = now,
            limit = limit * RUNNABLE_SCAN_MULTIPLIER
        )
        candidates.filter { it.attempts >= MAX_AUTOMATIC_ATTEMPTS }.forEach { exhaustedJob ->
            markFailedTerminal(exhaustedJob, "automatic_attempt_limit_reached")
        }
        return candidates.filter { it.attempts < MAX_AUTOMATIC_ATTEMPTS }.take(limit)
    }

    suspend fun nextScheduledRunAt(now: Long = now()): Long? =
        jobDao.getEarliestFutureRunAt(now)

    suspend fun nextScheduledDelaySeconds(now: Long = now()): Long? =
        nextScheduledRunAt(now)?.let { runAt -> (runAt - now).coerceAtLeast(0) }

    suspend fun retryManually(jobId: String): MemoryMaintenanceJob? {
        val job = jobDao.getById(jobId) ?: return null
        if (job.status !in MANUALLY_RETRYABLE_STATUSES) return null
        val now = now()
        val updated = job.copy(
            status = MemoryMaintenanceJobStatus.PENDING,
            attempts = 0,
            lastError = null,
            startedAt = null,
            updatedAt = now,
            nextRunAt = now
        )
        jobDao.update(updated)
        emitStatusChanged(job, updated, now)
        return updated
    }

    suspend fun markRunning(job: MemoryMaintenanceJob): MemoryMaintenanceJob {
        val now = now()
        val updated = job.copy(
            status = MemoryMaintenanceJobStatus.RUNNING,
            attempts = job.attempts + 1,
            lastError = null,
            startedAt = now,
            updatedAt = now,
            nextRunAt = null
        )
        jobDao.update(updated)
        emitStatusChanged(job, updated, now)
        return updated
    }

    suspend fun markSucceeded(job: MemoryMaintenanceJob): MemoryMaintenanceJob {
        val now = now()
        val updated = job.copy(
            status = MemoryMaintenanceJobStatus.SUCCEEDED,
            lastError = null,
            updatedAt = now,
            nextRunAt = null
        )
        jobDao.update(updated)
        emitStatusChanged(job, updated, now)
        return updated
    }

    suspend fun markFailedRetryable(
        job: MemoryMaintenanceJob,
        error: String,
        nextRunAt: Long? = retryAt(job.attempts)
    ): MemoryMaintenanceJob {
        if (job.attempts >= MAX_AUTOMATIC_ATTEMPTS) {
            return markFailedTerminal(job, error)
        }
        val now = now()
        val updated = job.copy(
            status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            lastError = error.take(MAX_ERROR_LENGTH),
            updatedAt = now,
            nextRunAt = nextRunAt
        )
        jobDao.update(updated)
        emitStatusChanged(job, updated, now)
        return updated
    }

    suspend fun markFailedTerminal(
        job: MemoryMaintenanceJob,
        error: String
    ): MemoryMaintenanceJob {
        val now = now()
        val updated = job.copy(
            status = MemoryMaintenanceJobStatus.FAILED_TERMINAL,
            lastError = error.take(MAX_ERROR_LENGTH),
            updatedAt = now,
            nextRunAt = null
        )
        jobDao.update(updated)
        emitStatusChanged(job, updated, now)
        return updated
    }

    suspend fun markDismissed(job: MemoryMaintenanceJob): MemoryMaintenanceJob {
        val now = now()
        val updated = job.copy(
            status = MemoryMaintenanceJobStatus.DISMISSED,
            updatedAt = now,
            nextRunAt = null
        )
        jobDao.update(updated)
        emitStatusChanged(job, updated, now)
        return updated
    }

    suspend fun resetStaleRunningJobs(
        olderThanSeconds: Long = STALE_RUNNING_SECONDS
    ): Int {
        val now = now()
        val staleJobs = jobDao.getStaleJobs(
            status = MemoryMaintenanceJobStatus.RUNNING,
            before = now - olderThanSeconds
        )
        staleJobs.forEach { job ->
            markFailedRetryable(
                job = job,
                error = "Job was interrupted before completion.",
                nextRunAt = now
            )
        }
        return staleJobs.size
    }

    private fun retryAt(attempts: Int): Long {
        val delaySeconds = when {
            attempts <= 1 -> 30L
            attempts == 2 -> 120L
            else -> 300L
        }
        return now() + delaySeconds
    }

    private fun now(): Long = clock.instant().epochSecond

    private suspend fun emitStatusChanged(
        oldJob: MemoryMaintenanceJob,
        newJob: MemoryMaintenanceJob,
        occurredAt: Long
    ) {
        runCatching {
            eventSink.onStatusChanged(
                MemoryMaintenanceStatusChangedEvent(
                    oldJob = oldJob,
                    newJob = newJob,
                    oldStatus = oldJob.status,
                    newStatus = newJob.status,
                    occurredAt = occurredAt
                )
            )
        }
    }

    companion object {
        private const val DEFAULT_RUNNABLE_LIMIT = 20
        const val MAX_AUTOMATIC_ATTEMPTS = 3
        private const val RUNNABLE_SCAN_MULTIPLIER = 4
        private const val MAX_ERROR_LENGTH = 500
        private const val STALE_RUNNING_SECONDS = 15 * 60L
        private val MANUALLY_RETRYABLE_STATUSES = setOf(
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            MemoryMaintenanceJobStatus.FAILED_TERMINAL
        )
    }
}

object MemoryMaintenanceJobType {
    const val APPEND_DAILY_NOTE = "append_daily_note"
    const val REBUILD_MEMORY_INDEX = "rebuild_memory_index"
    const val DISTILL_DAILY_NOTES = "distill_daily_notes"
    const val PROMOTE_LONG_TERM_CANDIDATE = "promote_long_term_candidate"
    const val REPAIR_MARKDOWN_METADATA = "repair_markdown_metadata"
    const val COMPACTION_FLUSH = "compaction_flush"
    const val CONSOLIDATE_TURN_BATCH = "consolidate_turn_batch"
}

object MemoryMaintenanceJobStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED_RETRYABLE = "failed_retryable"
    const val FAILED_TERMINAL = "failed_terminal"
    const val DISMISSED = "dismissed"
}
