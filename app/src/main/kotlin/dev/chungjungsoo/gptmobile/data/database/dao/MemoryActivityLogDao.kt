package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryActivityLog
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryActivityLogDao {

    @Query("SELECT * FROM memory_activity_log ORDER BY started_at DESC, log_id DESC LIMIT :limit")
    fun observeLatest(limit: Int = 200): Flow<List<MemoryActivityLog>>

    @Upsert
    suspend fun upsert(log: MemoryActivityLog)

    @Query(
        """
        UPDATE memory_activity_log
        SET status = :status,
            detail = :detail,
            operation_count = :operationCount,
            completed_at = :completedAt,
            updated_at = :updatedAt
        WHERE log_id = :logId
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
