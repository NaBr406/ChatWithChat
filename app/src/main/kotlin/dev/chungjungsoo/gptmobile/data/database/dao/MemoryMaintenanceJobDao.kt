package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
        WHERE status IN ('pending', 'running', 'failed_retryable', 'failed_terminal')
        ORDER BY updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun getVisibleJobs(limit: Int = 20): List<MemoryMaintenanceJob>

    @Query(
        """
        SELECT * FROM memory_maintenance_job
        WHERE status IN (:statuses)
            AND (:now >= COALESCE(next_run_at, 0))
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun getRunnableJobs(
        statuses: List<String>,
        now: Long,
        limit: Int
    ): List<MemoryMaintenanceJob>

    @Query(
        """
        SELECT MIN(next_run_at) FROM memory_maintenance_job
        WHERE status IN ('pending', 'failed_retryable')
            AND next_run_at IS NOT NULL
            AND next_run_at > :now
        """
    )
    suspend fun getEarliestFutureRunAt(now: Long): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(job: MemoryMaintenanceJob): Long

    @Update
    suspend fun update(job: MemoryMaintenanceJob)

    @Query(
        """
        UPDATE memory_maintenance_job
        SET status = :status,
            last_error = :lastError,
            updated_at = :updatedAt,
            next_run_at = :nextRunAt
        WHERE status = :fromStatus
            AND updated_at < :before
        """
    )
    suspend fun moveStaleJobs(
        fromStatus: String,
        status: String,
        before: Long,
        lastError: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int
}
