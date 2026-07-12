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
        jobId: String = UUID.randomUUID().toString(),
        generation: Long = 0
    ): MemoryMaintenanceJob {
        val persistedIdempotencyKey = persistedIdempotencyKey(type, idempotencyKey, generation)
        jobDao.getByIdempotencyKey(persistedIdempotencyKey)?.let { return it }

        val now = now()
        val job = MemoryMaintenanceJob(
            jobId = jobId,
            type = type,
            status = MemoryMaintenanceJobStatus.PENDING,
            idempotencyKey = persistedIdempotencyKey,
            payloadJson = payloadJson,
            attempts = 0,
            lastError = null,
            createdAt = now,
            startedAt = null,
            updatedAt = now,
            nextRunAt = nextRunAt,
            family = MemoryMaintenanceJobFamily.forType(type),
            generation = generation,
            rowVersion = 0,
            leaseOwner = null,
            leaseExpiresAt = null,
            retryCycle = 0,
            blockedReason = null
        )
        val inserted = jobDao.insertIgnore(job)
        if (inserted != -1L) return job

        return jobDao.getByIdempotencyKey(persistedIdempotencyKey) ?: job
    }

    suspend fun claimNextRunnable(
        family: String,
        leaseOwner: String,
        now: Long = now()
    ): MemoryMaintenanceJob? {
        require(family in MemoryMaintenanceJobFamily.ALL) { "Unknown memory maintenance family: $family" }
        val claimed = jobDao.claimNextRunnable(
            family = family,
            leaseOwner = leaseOwner,
            now = now,
            leaseExpiresAt = now + leaseDurationSeconds(family)
        ) ?: return null
        emitStatusChanged(oldJob = null, newJob = claimed, occurredAt = now)
        return claimed
    }

    suspend fun renewClaimedLease(
        job: MemoryMaintenanceJob,
        now: Long = now()
    ): MemoryMaintenanceJob {
        val leaseOwner = job.leaseOwner ?: throw MemoryMaintenanceLeaseLostException(job.jobId)
        val leaseExpiresAt = now + leaseDurationSeconds(job.family)
        val changed = jobDao.renewClaimedLease(
            jobId = job.jobId,
            leaseOwner = leaseOwner,
            expectedRowVersion = job.rowVersion,
            now = now,
            leaseExpiresAt = leaseExpiresAt
        )
        if (changed != 1) throw MemoryMaintenanceLeaseLostException(job.jobId)
        return job.copy(leaseExpiresAt = maxOf(checkNotNull(job.leaseExpiresAt), leaseExpiresAt))
    }

    suspend fun nextScheduledRunAt(
        family: String,
        now: Long = now()
    ): Long? = jobDao.getEarliestFutureRunAtForFamily(family, now)

    suspend fun nextScheduledDelaySeconds(
        family: String,
        now: Long = now()
    ): Long? = nextScheduledRunAt(family, now)?.let { runAt -> (runAt - now).coerceAtLeast(0) }

    suspend fun hasRunnableJob(
        family: String,
        now: Long = now()
    ): Boolean = jobDao.hasRunnableJob(family, now)

    suspend fun nextRepairWakeAt(now: Long = now()): Long? = listOfNotNull(
        nextScheduledRunAt(MemoryMaintenanceJobFamily.REPAIR, now),
        jobDao.getEarliestLeaseExpiry(now)
    ).minOrNull()

    suspend fun nextRepairDelaySeconds(now: Long = now()): Long? =
        nextRepairWakeAt(now)?.let { runAt -> (runAt - now).coerceAtLeast(0) }

    suspend fun retryManually(jobId: String): MemoryMaintenanceJob? {
        val job = jobDao.getById(jobId) ?: return null
        if (job.status !in MANUALLY_RETRYABLE_STATUSES) return null
        val now = now()
        val changed = jobDao.transitionUnclaimedJob(
            jobId = job.jobId,
            expectedStatus = job.status,
            expectedRowVersion = job.rowVersion,
            newStatus = MemoryMaintenanceJobStatus.PENDING,
            attempts = 0,
            retryCycle = job.retryCycle + 1,
            lastError = null,
            blockedReason = null,
            updatedAt = now,
            nextRunAt = now
        )
        if (changed != 1) return null
        val updated = checkNotNull(jobDao.getById(job.jobId))
        emitStatusChanged(job, updated, now)
        return updated
    }

    suspend fun markSucceeded(job: MemoryMaintenanceJob): MemoryMaintenanceJob =
        transitionClaimed(
            job = job,
            status = MemoryMaintenanceJobStatus.SUCCEEDED,
            lastError = null,
            blockedReason = null,
            nextRunAt = null
        )

    suspend fun markFailedRetryable(
        job: MemoryMaintenanceJob,
        error: String
    ): MemoryMaintenanceJob {
        val policy = retryPolicy(job.family)
        if (job.attempts >= policy.maxAutomaticAttempts) {
            return transitionClaimed(
                job = job,
                status = policy.exhaustedStatus,
                lastError = error,
                blockedReason = error,
                nextRunAt = null
            )
        }
        return transitionClaimed(
            job = job,
            status = MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            lastError = error,
            blockedReason = null,
            nextRunAt = now() + policy.delayAfterAttempt(job.attempts)
        )
    }

    suspend fun markFailedTerminal(
        job: MemoryMaintenanceJob,
        error: String
    ): MemoryMaintenanceJob = transition(
        job = job,
        status = MemoryMaintenanceJobStatus.FAILED_TERMINAL,
        lastError = error,
        blockedReason = error,
        nextRunAt = null
    )

    suspend fun markBlockedDependency(
        job: MemoryMaintenanceJob,
        reason: String
    ): MemoryMaintenanceJob = transition(
        job = job,
        status = MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY,
        lastError = reason,
        blockedReason = reason,
        nextRunAt = null
    )

    suspend fun markDismissed(
        job: MemoryMaintenanceJob,
        reason: String? = null
    ): MemoryMaintenanceJob = transition(
        job = job,
        status = MemoryMaintenanceJobStatus.DISMISSED,
        lastError = reason,
        blockedReason = null,
        nextRunAt = null
    )

    suspend fun resetExpiredRunningJobs(
        limit: Int = DEFAULT_EXPIRED_LEASE_LIMIT,
        now: Long = now()
    ): Int {
        var resetCount = 0
        jobDao.getExpiredLeases(now = now, limit = limit).forEach { job ->
            val policy = retryPolicy(job.family)
            val exhausted = job.attempts >= policy.maxAutomaticAttempts
            val status = if (exhausted) policy.exhaustedStatus else MemoryMaintenanceJobStatus.FAILED_RETRYABLE
            val reason = "job_lease_expired"
            val changed = jobDao.reclaimExpiredLease(
                jobId = job.jobId,
                expectedLeaseOwner = job.leaseOwner,
                expectedRowVersion = job.rowVersion,
                now = now,
                newStatus = status,
                lastError = reason,
                blockedReason = reason.takeIf { exhausted },
                updatedAt = now,
                nextRunAt = now.takeUnless { exhausted }
            )
            if (changed == 1) {
                resetCount += 1
                jobDao.getById(job.jobId)?.let { updated -> emitStatusChanged(job, updated, now) }
            }
        }
        return resetCount
    }

    suspend fun reopenWaitingRepairJobs(limit: Int = DEFAULT_VISIBLE_LIMIT): Int {
        return jobDao.getReopenableLocalJobs(limit).count { job -> retryManually(job.jobId) != null }
    }

    private suspend fun transition(
        job: MemoryMaintenanceJob,
        status: String,
        lastError: String?,
        blockedReason: String?,
        nextRunAt: Long?
    ): MemoryMaintenanceJob = if (job.status == MemoryMaintenanceJobStatus.RUNNING) {
        transitionClaimed(job, status, lastError, blockedReason, nextRunAt)
    } else {
        transitionUnclaimed(job, status, lastError, blockedReason, nextRunAt)
    }

    private suspend fun transitionClaimed(
        job: MemoryMaintenanceJob,
        status: String,
        lastError: String?,
        blockedReason: String?,
        nextRunAt: Long?
    ): MemoryMaintenanceJob {
        val leaseOwner = job.leaseOwner ?: throw MemoryMaintenanceLeaseLostException(job.jobId)
        val now = now()
        val changed = jobDao.transitionClaimedJob(
            jobId = job.jobId,
            leaseOwner = leaseOwner,
            expectedRowVersion = job.rowVersion,
            newStatus = status,
            lastError = lastError?.take(MAX_ERROR_LENGTH),
            blockedReason = blockedReason?.take(MAX_ERROR_LENGTH),
            updatedAt = now,
            nextRunAt = nextRunAt
        )
        if (changed != 1) throw MemoryMaintenanceLeaseLostException(job.jobId)
        val updated = checkNotNull(jobDao.getById(job.jobId))
        emitStatusChanged(job, updated, now)
        return updated
    }

    private suspend fun transitionUnclaimed(
        job: MemoryMaintenanceJob,
        status: String,
        lastError: String?,
        blockedReason: String?,
        nextRunAt: Long?
    ): MemoryMaintenanceJob {
        val now = now()
        val changed = jobDao.transitionUnclaimedJob(
            jobId = job.jobId,
            expectedStatus = job.status,
            expectedRowVersion = job.rowVersion,
            newStatus = status,
            attempts = job.attempts,
            retryCycle = job.retryCycle,
            lastError = lastError?.take(MAX_ERROR_LENGTH),
            blockedReason = blockedReason?.take(MAX_ERROR_LENGTH),
            updatedAt = now,
            nextRunAt = nextRunAt
        )
        if (changed != 1) throw MemoryMaintenanceLeaseLostException(job.jobId)
        val updated = checkNotNull(jobDao.getById(job.jobId))
        emitStatusChanged(job, updated, now)
        return updated
    }

    private fun retryPolicy(family: String): MemoryMaintenanceRetryPolicy = when (family) {
        MemoryMaintenanceJobFamily.SEMANTIC -> SEMANTIC_RETRY_POLICY
        MemoryMaintenanceJobFamily.INDEX,
        MemoryMaintenanceJobFamily.REPAIR -> LOCAL_RETRY_POLICY
        else -> LOCAL_RETRY_POLICY
    }

    private fun leaseDurationSeconds(family: String): Long = when (family) {
        MemoryMaintenanceJobFamily.SEMANTIC -> SEMANTIC_LEASE_SECONDS
        else -> LOCAL_LEASE_SECONDS
    }

    private fun persistedIdempotencyKey(type: String, idempotencyKey: String, generation: Long): String {
        if (type !in GENERATION_AWARE_TYPES) return idempotencyKey
        require(generation > 0) { "$type requires a positive corpus generation" }
        return "$idempotencyKey:generation:$generation"
    }

    private fun now(): Long = clock.instant().epochSecond

    private suspend fun emitStatusChanged(
        oldJob: MemoryMaintenanceJob?,
        newJob: MemoryMaintenanceJob,
        occurredAt: Long
    ) {
        runCatching {
            eventSink.onStatusChanged(
                MemoryMaintenanceStatusChangedEvent(
                    oldJob = oldJob,
                    newJob = newJob,
                    oldStatus = oldJob?.status,
                    newStatus = newJob.status,
                    occurredAt = occurredAt
                )
            )
        }
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 500
        private const val DEFAULT_EXPIRED_LEASE_LIMIT = 100
        private const val DEFAULT_VISIBLE_LIMIT = 200
        private const val SEMANTIC_LEASE_SECONDS = 30 * 60L
        private const val LOCAL_LEASE_SECONDS = 15 * 60L
        private val GENERATION_AWARE_TYPES = setOf(
            MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
            MemoryMaintenanceJobType.REBUILD_VECTOR_INDEX
        )
        private val MANUALLY_RETRYABLE_STATUSES = setOf(
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            MemoryMaintenanceJobStatus.FAILED_TERMINAL,
            MemoryMaintenanceJobStatus.WAITING_REPAIR
        )
        private val SEMANTIC_RETRY_POLICY = MemoryMaintenanceRetryPolicy(
            maxAutomaticAttempts = 3,
            retryDelaysSeconds = listOf(30L, 120L),
            exhaustedStatus = MemoryMaintenanceJobStatus.FAILED_TERMINAL
        )
        private val LOCAL_RETRY_POLICY = MemoryMaintenanceRetryPolicy(
            maxAutomaticAttempts = 5,
            retryDelaysSeconds = listOf(30L, 120L, 600L, 3_600L),
            exhaustedStatus = MemoryMaintenanceJobStatus.WAITING_REPAIR
        )
    }
}

private data class MemoryMaintenanceRetryPolicy(
    val maxAutomaticAttempts: Int,
    val retryDelaysSeconds: List<Long>,
    val exhaustedStatus: String
) {
    fun delayAfterAttempt(attempts: Int): Long =
        retryDelaysSeconds.getOrElse((attempts - 1).coerceAtLeast(0)) { retryDelaysSeconds.last() }
}

class MemoryMaintenanceLeaseLostException(jobId: String) :
    IllegalStateException("Memory maintenance lease lost for job $jobId")

object MemoryMaintenanceJobFamily {
    const val SEMANTIC = "semantic"
    const val INDEX = "index"
    const val REPAIR = "repair"

    val ALL = setOf(SEMANTIC, INDEX, REPAIR)

    fun forType(type: String): String = when (type) {
        MemoryMaintenanceJobType.REBUILD_MEMORY_INDEX,
        MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
        MemoryMaintenanceJobType.REBUILD_VECTOR_INDEX -> INDEX
        MemoryMaintenanceJobType.REPAIR_MARKDOWN_METADATA -> REPAIR
        MemoryMaintenanceJobType.APPEND_DAILY_NOTE,
        MemoryMaintenanceJobType.DISTILL_DAILY_NOTES,
        MemoryMaintenanceJobType.PROMOTE_LONG_TERM_CANDIDATE,
        MemoryMaintenanceJobType.COMPACTION_FLUSH,
        MemoryMaintenanceJobType.CONSOLIDATE_TURN_BATCH -> SEMANTIC
        else -> REPAIR
    }
}

object MemoryMaintenanceJobType {
    const val APPEND_DAILY_NOTE = "append_daily_note"
    const val REBUILD_MEMORY_INDEX = "rebuild_memory_index"
    const val SYNC_VECTOR_INDEX = "sync_vector_index"
    const val REBUILD_VECTOR_INDEX = "rebuild_vector_index"
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
    const val WAITING_REPAIR = "waiting_repair"
    const val BLOCKED_DEPENDENCY = "blocked_dependency"
    const val DISMISSED = "dismissed"
}
