package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryActivityLogDao {

    @Query("SELECT * FROM memory_activity_log ORDER BY started_at DESC, log_id DESC LIMIT :limit")
    fun observeLatest(limit: Int = 200): Flow<List<MemoryActivityLog>>

    @Upsert
    suspend fun upsert(log: MemoryActivityLog)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRun(log: MemoryActivityLog): Long

    @Query("SELECT * FROM memory_activity_log WHERE log_id = :activityRunId LIMIT 1")
    suspend fun getById(activityRunId: String): MemoryActivityLog?

    @Query(
        """
        SELECT * FROM memory_activity_log
        WHERE job_id IS NOT NULL
          AND phase IS NOT NULL
          AND status IN ('scheduled', 'running')
        ORDER BY started_at ASC, log_id ASC
        LIMIT :limit
        """
    )
    suspend fun getActiveJobRuns(limit: Int = 200): List<MemoryActivityLog>

    @Query(
        """
        SELECT * FROM memory_activity_log
        WHERE job_id = :jobId
          AND retry_cycle = :retryCycle
          AND attempt = :attempt
        LIMIT 1
        """
    )
    suspend fun getRun(jobId: String, retryCycle: Int, attempt: Int): MemoryActivityLog?

    @Query(
        """
        UPDATE memory_activity_log
        SET status = 'running',
            phase = :nextPhase,
            platform_uid = :platformUid,
            model_id = :modelId,
            platform_name = :platformName,
            model_name = :modelName,
            input_count = :inputCount,
            operation_count = :operationCount,
            error_code = NULL,
            phase_summary_json = :phaseSummaryJson,
            updated_at = :updatedAt,
            row_version = row_version + 1
        WHERE log_id = :activityRunId
          AND row_version = :expectedRowVersion
          AND phase = :expectedPhase
          AND status IN ('scheduled', 'running')
        """
    )
    suspend fun advanceRun(
        activityRunId: String,
        expectedRowVersion: Long,
        expectedPhase: String,
        nextPhase: String,
        platformUid: String?,
        modelId: String?,
        platformName: String?,
        modelName: String?,
        inputCount: Int?,
        operationCount: Int?,
        phaseSummaryJson: String,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_activity_log
        SET status = :status,
            platform_uid = :platformUid,
            model_id = :modelId,
            platform_name = :platformName,
            model_name = :modelName,
            input_count = :inputCount,
            operation_count = :operationCount,
            error_code = :errorCode,
            phase_summary_json = :phaseSummaryJson,
            completed_at = :completedAt,
            updated_at = :updatedAt,
            row_version = row_version + 1
        WHERE log_id = :activityRunId
          AND row_version = :expectedRowVersion
          AND phase = :expectedPhase
          AND status IN ('scheduled', 'running')
        """
    )
    suspend fun finishRun(
        activityRunId: String,
        expectedRowVersion: Long,
        expectedPhase: String,
        status: String,
        platformUid: String?,
        modelId: String?,
        platformName: String?,
        modelName: String?,
        inputCount: Int?,
        operationCount: Int?,
        errorCode: String?,
        phaseSummaryJson: String,
        completedAt: Long,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_activity_log
        SET status = :status,
            detail = :detail,
            operation_count = :operationCount,
            completed_at = :completedAt,
            updated_at = :updatedAt
        WHERE log_id = :logId
          AND phase IS NULL
        """
    )
    suspend fun finish(
        logId: String,
        status: String,
        detail: String?,
        operationCount: Int?,
        completedAt: Long,
        updatedAt: Long
    )

    @Query("DELETE FROM memory_activity_log WHERE started_at < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
