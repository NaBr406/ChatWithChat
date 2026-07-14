package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob

@Dao
interface MemoryMaintenanceJobDao {

    @Query("SELECT * FROM memory_maintenance_job WHERE job_id = :jobId LIMIT 1")
    suspend fun getById(jobId: String): MemoryMaintenanceJob?

    @Query("SELECT * FROM memory_maintenance_job WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(idempotencyKey: String): MemoryMaintenanceJob?

    @Query(
        """
        SELECT * FROM memory_maintenance_job
        WHERE type = :type AND status IN (:statuses)
        ORDER BY created_at ASC
        """
    )
    suspend fun getByTypeAndStatuses(type: String, statuses: List<String>): List<MemoryMaintenanceJob>

    @Query(
        """
        SELECT * FROM memory_maintenance_job
        WHERE status IN (
            'pending',
            'running',
            'failed_retryable',
            'failed_terminal',
            'waiting_repair',
            'blocked_dependency'
        )
        ORDER BY updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun getVisibleJobs(limit: Int = 20): List<MemoryMaintenanceJob>

    @Query(
        """
        SELECT * FROM memory_maintenance_job
        WHERE family IN ('index', 'repair')
            AND status IN ('waiting_repair', 'failed_terminal')
        ORDER BY updated_at ASC, job_id ASC
        LIMIT :limit
        """
    )
    suspend fun getReopenableLocalJobs(limit: Int): List<MemoryMaintenanceJob>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM memory_maintenance_job
            WHERE family = :family
                AND status IN ('pending', 'failed_retryable')
                AND COALESCE(next_run_at, 0) <= :now
                AND (
                    :family != 'semantic'
                    OR NOT EXISTS (
                        SELECT 1 FROM memory_maintenance_job AS active_semantic
                        WHERE active_semantic.family = 'semantic'
                            AND active_semantic.status = 'running'
                    )
                )
        )
        """
    )
    suspend fun hasRunnableJob(family: String, now: Long): Boolean

    @Query(
        """
        SELECT * FROM memory_maintenance_job
        WHERE family = :family
            AND status IN ('pending', 'failed_retryable')
            AND COALESCE(next_run_at, 0) <= :now
        ORDER BY created_at ASC, job_id ASC
        LIMIT 1
        """
    )
    suspend fun getNextRunnableCandidate(
        family: String,
        now: Long
    ): MemoryMaintenanceJob?

    @Query(
        """
        UPDATE memory_maintenance_job
        SET status = 'running',
            attempts = attempts + 1,
            row_version = row_version + 1,
            lease_owner = :leaseOwner,
            lease_expires_at = :leaseExpiresAt,
            last_error = NULL,
            blocked_reason = NULL,
            started_at = :now,
            updated_at = :now,
            next_run_at = NULL
        WHERE job_id = :jobId
            AND family = :family
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND COALESCE(next_run_at, 0) <= :now
            AND (
                :family != 'semantic'
                OR NOT EXISTS (
                    SELECT 1 FROM memory_maintenance_job AS active_lease
                    WHERE active_lease.family = 'semantic'
                        AND active_lease.status = 'running'
                        AND active_lease.job_id != :jobId
                )
            )
        """
    )
    suspend fun claimRunnableCandidate(
        jobId: String,
        family: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        leaseOwner: String,
        now: Long,
        leaseExpiresAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_maintenance_job
        SET lease_expires_at = CASE
                WHEN lease_expires_at < :leaseExpiresAt THEN :leaseExpiresAt
                ELSE lease_expires_at
            END
        WHERE job_id = :jobId
            AND status = 'running'
            AND lease_owner = :leaseOwner
            AND row_version = :expectedRowVersion
            AND lease_expires_at > :now
        """
    )
    suspend fun renewClaimedLease(
        jobId: String,
        leaseOwner: String,
        expectedRowVersion: Long,
        now: Long,
        leaseExpiresAt: Long
    ): Int

    @Transaction
    suspend fun claimNextRunnable(
        family: String,
        leaseOwner: String,
        now: Long,
        leaseExpiresAt: Long
    ): MemoryMaintenanceJob? {
        require(family.isNotBlank()) { "Memory maintenance family must not be blank" }
        require(leaseOwner.isNotBlank()) { "Memory maintenance lease owner must not be blank" }
        require(leaseExpiresAt > now) { "Memory maintenance lease must expire after claim time" }

        val candidate = getNextRunnableCandidate(family = family, now = now) ?: return null
        val claimed = claimRunnableCandidate(
            jobId = candidate.jobId,
            family = family,
            expectedStatus = candidate.status,
            expectedRowVersion = candidate.rowVersion,
            leaseOwner = leaseOwner,
            now = now,
            leaseExpiresAt = leaseExpiresAt
        )
        if (claimed != 1) return null

        return getById(candidate.jobId)
    }

    @Query(
        """
        UPDATE memory_maintenance_job
        SET status = :newStatus,
            last_error = :lastError,
            blocked_reason = :blockedReason,
            started_at = NULL,
            updated_at = :updatedAt,
            next_run_at = :nextRunAt,
            lease_owner = NULL,
            lease_expires_at = NULL,
            row_version = row_version + 1
        WHERE job_id = :jobId
            AND status = 'running'
            AND lease_owner = :leaseOwner
            AND row_version = :expectedRowVersion
            AND lease_expires_at > :updatedAt
        """
    )
    suspend fun transitionClaimedJob(
        jobId: String,
        leaseOwner: String,
        expectedRowVersion: Long,
        newStatus: String,
        lastError: String?,
        blockedReason: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int

    @Query(
        """
        UPDATE memory_maintenance_job
        SET status = :newStatus,
            attempts = :attempts,
            retry_cycle = :retryCycle,
            last_error = :lastError,
            blocked_reason = :blockedReason,
            started_at = NULL,
            updated_at = :updatedAt,
            next_run_at = :nextRunAt,
            lease_owner = NULL,
            lease_expires_at = NULL,
            row_version = row_version + 1
        WHERE job_id = :jobId
            AND status = :expectedStatus
            AND status != 'running'
            AND row_version = :expectedRowVersion
        """
    )
    suspend fun transitionUnclaimedJob(
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
    ): Int

    @Query(
        """
        SELECT * FROM memory_maintenance_job
        WHERE status = 'running'
            AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
        ORDER BY updated_at ASC, job_id ASC
        LIMIT :limit
        """
    )
    suspend fun getExpiredLeases(
        now: Long,
        limit: Int
    ): List<MemoryMaintenanceJob>

    @Query(
        """
        UPDATE memory_maintenance_job
        SET status = :newStatus,
            last_error = :lastError,
            blocked_reason = :blockedReason,
            started_at = NULL,
            updated_at = :updatedAt,
            next_run_at = :nextRunAt,
            lease_owner = NULL,
            lease_expires_at = NULL,
            row_version = row_version + 1
        WHERE job_id = :jobId
            AND status = 'running'
            AND row_version = :expectedRowVersion
            AND (
                lease_owner = :expectedLeaseOwner
                OR (lease_owner IS NULL AND :expectedLeaseOwner IS NULL)
            )
            AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
        """
    )
    suspend fun reclaimExpiredLease(
        jobId: String,
        expectedLeaseOwner: String?,
        expectedRowVersion: Long,
        now: Long,
        newStatus: String,
        lastError: String,
        blockedReason: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int

    @Query(
        """
        SELECT MIN(next_run_at) FROM memory_maintenance_job
        WHERE family = :family
            AND status IN ('pending', 'failed_retryable')
            AND next_run_at IS NOT NULL
            AND next_run_at > :now
        """
    )
    suspend fun getEarliestFutureRunAtForFamily(
        family: String,
        now: Long
    ): Long?

    @Query(
        """
        SELECT MIN(COALESCE(lease_expires_at, :now))
        FROM memory_maintenance_job
        WHERE status = 'running'
        """
    )
    suspend fun getEarliestLeaseExpiry(now: Long): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(job: MemoryMaintenanceJob): Long
}
