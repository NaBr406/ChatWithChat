package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob

internal class InMemoryMaintenanceJobDao(
    initialJobs: List<MemoryMaintenanceJob> = emptyList()
) : MemoryMaintenanceJobDao {
    val jobs = initialJobs.toMutableList()

    override suspend fun getById(jobId: String): MemoryMaintenanceJob? =
        jobs.firstOrNull { it.jobId == jobId }

    override suspend fun getByIdempotencyKey(idempotencyKey: String): MemoryMaintenanceJob? =
        jobs.firstOrNull { it.idempotencyKey == idempotencyKey }

    override suspend fun getByTypeAndStatuses(type: String, statuses: List<String>): List<MemoryMaintenanceJob> =
        jobs.filter { it.type == type && it.status in statuses }.sortedBy { it.createdAt }

    override suspend fun getVisibleJobs(limit: Int): List<MemoryMaintenanceJob> =
        jobs.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun getReopenableLocalJobs(limit: Int): List<MemoryMaintenanceJob> =
        jobs
            .filter { job ->
                job.family in setOf(MemoryMaintenanceJobFamily.INDEX, MemoryMaintenanceJobFamily.REPAIR) &&
                    job.status in setOf(
                        MemoryMaintenanceJobStatus.WAITING_REPAIR,
                        MemoryMaintenanceJobStatus.FAILED_TERMINAL
                    )
            }
            .sortedWith(compareBy<MemoryMaintenanceJob> { it.updatedAt }.thenBy { it.jobId })
            .take(limit)

    override suspend fun hasRunnableJob(family: String, now: Long): Boolean =
        (
            family != MemoryMaintenanceJobFamily.SEMANTIC ||
                jobs.none { job ->
                    job.family == MemoryMaintenanceJobFamily.SEMANTIC &&
                        job.status == MemoryMaintenanceJobStatus.RUNNING
                }
            ) &&
            jobs.any { job ->
                job.family == family &&
                    job.status in RUNNABLE_STATUSES &&
                    now >= (job.nextRunAt ?: 0L)
            }

    override suspend fun getNextRunnableCandidate(family: String, now: Long): MemoryMaintenanceJob? =
        jobs
            .filter { job -> job.family == family }
            .filter { job -> job.status in RUNNABLE_STATUSES }
            .filter { job -> now >= (job.nextRunAt ?: 0L) }
            .minWithOrNull(compareBy<MemoryMaintenanceJob> { it.createdAt }.thenBy { it.jobId })

    override suspend fun claimRunnableCandidate(
        jobId: String,
        family: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        leaseOwner: String,
        now: Long,
        leaseExpiresAt: Long
    ): Int {
        val index = jobs.indexOfFirst { job ->
            job.jobId == jobId &&
                job.family == family &&
                job.status == expectedStatus &&
                job.rowVersion == expectedRowVersion &&
                now >= (job.nextRunAt ?: 0L)
        }
        if (index == -1) return 0
        if (
            family == MemoryMaintenanceJobFamily.SEMANTIC &&
            jobs.any { job ->
                job.jobId != jobId &&
                    job.family == MemoryMaintenanceJobFamily.SEMANTIC &&
                    job.status == MemoryMaintenanceJobStatus.RUNNING
            }
        ) {
            return 0
        }
        jobs[index] = jobs[index].copy(
            status = MemoryMaintenanceJobStatus.RUNNING,
            attempts = jobs[index].attempts + 1,
            lastError = null,
            startedAt = now,
            updatedAt = now,
            nextRunAt = null,
            rowVersion = jobs[index].rowVersion + 1,
            leaseOwner = leaseOwner,
            leaseExpiresAt = leaseExpiresAt,
            blockedReason = null
        )
        return 1
    }

    override suspend fun renewClaimedLease(
        jobId: String,
        leaseOwner: String,
        expectedRowVersion: Long,
        now: Long,
        leaseExpiresAt: Long
    ): Int {
        val index = jobs.indexOfFirst { job ->
            job.jobId == jobId &&
                job.status == MemoryMaintenanceJobStatus.RUNNING &&
                job.leaseOwner == leaseOwner &&
                job.rowVersion == expectedRowVersion &&
                (job.leaseExpiresAt ?: Long.MIN_VALUE) > now
        }
        if (index == -1) return 0
        jobs[index] = jobs[index].copy(
            leaseExpiresAt = maxOf(checkNotNull(jobs[index].leaseExpiresAt), leaseExpiresAt)
        )
        return 1
    }

    override suspend fun transitionClaimedJob(
        jobId: String,
        leaseOwner: String,
        expectedRowVersion: Long,
        newStatus: String,
        lastError: String?,
        blockedReason: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int {
        val index = jobs.indexOfFirst { job ->
            job.jobId == jobId &&
                job.status == MemoryMaintenanceJobStatus.RUNNING &&
                job.leaseOwner == leaseOwner &&
                job.rowVersion == expectedRowVersion &&
                (job.leaseExpiresAt ?: Long.MIN_VALUE) > updatedAt
        }
        if (index == -1) return 0
        jobs[index] = jobs[index].copy(
            status = newStatus,
            lastError = lastError,
            blockedReason = blockedReason,
            updatedAt = updatedAt,
            nextRunAt = nextRunAt,
            rowVersion = jobs[index].rowVersion + 1,
            leaseOwner = null,
            leaseExpiresAt = null
        )
        return 1
    }

    override suspend fun transitionUnclaimedJob(
        jobId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        newStatus: String,
        attempts: Int,
        retryCycle: Int,
        lastError: String?,
        blockedReason: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int {
        val index = jobs.indexOfFirst { job ->
            job.jobId == jobId &&
                job.status == expectedStatus &&
                job.status != MemoryMaintenanceJobStatus.RUNNING &&
                job.rowVersion == expectedRowVersion
        }
        if (index == -1) return 0
        jobs[index] = jobs[index].copy(
            status = newStatus,
            attempts = attempts,
            retryCycle = retryCycle,
            lastError = lastError,
            blockedReason = blockedReason,
            startedAt = null,
            updatedAt = updatedAt,
            nextRunAt = nextRunAt,
            rowVersion = jobs[index].rowVersion + 1,
            leaseOwner = null,
            leaseExpiresAt = null
        )
        return 1
    }

    override suspend fun getExpiredLeases(now: Long, limit: Int): List<MemoryMaintenanceJob> =
        jobs
            .filter { job ->
                job.status == MemoryMaintenanceJobStatus.RUNNING &&
                    (job.leaseExpiresAt == null || job.leaseExpiresAt <= now)
            }
            .sortedWith(compareBy<MemoryMaintenanceJob> { it.updatedAt }.thenBy { it.jobId })
            .take(limit)

    override suspend fun reclaimExpiredLease(
        jobId: String,
        expectedLeaseOwner: String?,
        expectedRowVersion: Long,
        now: Long,
        newStatus: String,
        lastError: String,
        blockedReason: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int {
        val index = jobs.indexOfFirst { job ->
            job.jobId == jobId &&
                job.status == MemoryMaintenanceJobStatus.RUNNING &&
                job.leaseOwner == expectedLeaseOwner &&
                job.rowVersion == expectedRowVersion &&
                (job.leaseExpiresAt == null || job.leaseExpiresAt <= now)
        }
        if (index == -1) return 0
        jobs[index] = jobs[index].copy(
            status = newStatus,
            lastError = lastError,
            blockedReason = blockedReason,
            startedAt = null,
            updatedAt = updatedAt,
            nextRunAt = nextRunAt,
            rowVersion = jobs[index].rowVersion + 1,
            leaseOwner = null,
            leaseExpiresAt = null
        )
        return 1
    }

    override suspend fun getEarliestFutureRunAtForFamily(family: String, now: Long): Long? =
        jobs.asSequence()
            .filter { job -> job.family == family && job.status in RUNNABLE_STATUSES }
            .mapNotNull { job -> job.nextRunAt }
            .filter { runAt -> runAt > now }
            .minOrNull()

    override suspend fun getEarliestLeaseExpiry(now: Long): Long? =
        jobs.asSequence()
            .filter { job -> job.status == MemoryMaintenanceJobStatus.RUNNING }
            .map { job -> job.leaseExpiresAt ?: now }
            .minOrNull()

    override suspend fun insertIgnore(job: MemoryMaintenanceJob): Long {
        if (jobs.any { it.idempotencyKey == job.idempotencyKey }) return -1L
        jobs += job
        return jobs.size.toLong()
    }

    fun forceUpdate(job: MemoryMaintenanceJob) {
        val index = jobs.indexOfFirst { it.jobId == job.jobId }
        if (index != -1) jobs[index] = job
    }

    private companion object {
        val RUNNABLE_STATUSES = setOf(
            MemoryMaintenanceJobStatus.PENDING,
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE
        )
    }
}

internal class RecordingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
    val works = mutableListOf<EnqueuedMemoryWork>()
    val enqueueCalls: Int
        get() = works.size
    val delays: List<Long>
        get() = works.map { it.delaySeconds }

    override fun enqueueWork(family: String, delaySeconds: Long) {
        works += EnqueuedMemoryWork(family, delaySeconds)
    }
}

internal data class EnqueuedMemoryWork(
    val family: String,
    val delaySeconds: Long
)
