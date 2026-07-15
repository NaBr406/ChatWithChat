package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cn.nabr.chatwithchat.data.database.entity.MemoryChatCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn

@Dao
interface MemoryTurnBatchDao {

    @Query("SELECT * FROM memory_chat_checkpoint WHERE chat_id = :chatId LIMIT 1")
    suspend fun getCheckpoint(chatId: Int): MemoryChatCheckpoint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: MemoryChatCheckpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingTurn(turn: MemoryPendingTurn)

    @Query(
        """
        SELECT * FROM memory_pending_turn
        WHERE chat_id = :chatId AND user_message_id = :userMessageId
        LIMIT 1
        """
    )
    suspend fun getPendingTurn(chatId: Int, userMessageId: Int): MemoryPendingTurn?

    @Query(
        """
        SELECT * FROM memory_pending_turn
        WHERE chat_id = :chatId
        ORDER BY completed_at ASC, user_message_id ASC
        """
    )
    suspend fun getPendingTurnsForChat(chatId: Int): List<MemoryPendingTurn>

    @Query(
        """
        SELECT * FROM memory_pending_turn
        WHERE chat_id = :chatId AND claimed_job_id IS NULL
        ORDER BY completed_at ASC, user_message_id ASC
        LIMIT :limit
        """
    )
    suspend fun getOldestUnclaimedTurns(chatId: Int, limit: Int): List<MemoryPendingTurn>

    @Query(
        """
        SELECT * FROM memory_pending_turn
        WHERE claimed_job_id = :jobId
        ORDER BY completed_at ASC, user_message_id ASC
        """
    )
    suspend fun getTurnsClaimedByJob(jobId: String): List<MemoryPendingTurn>

    @Query(
        """
        SELECT DISTINCT claimed_job_id FROM memory_pending_turn
        WHERE claimed_job_id IS NOT NULL
        ORDER BY claimed_job_id ASC
        """
    )
    suspend fun getClaimedJobIds(): List<String>

    @Query(
        """
        SELECT DISTINCT chat_id FROM memory_pending_turn
        WHERE claimed_job_id IS NULL
        ORDER BY chat_id ASC
        """
    )
    suspend fun getChatIdsWithUnclaimedTurns(): List<Int>

    @Query(
        """
        SELECT COUNT(*) FROM memory_pending_turn
        WHERE chat_id = :chatId AND claimed_job_id IS NULL
        """
    )
    suspend fun countUnclaimedTurns(chatId: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM memory_pending_turn
        WHERE chat_id = :chatId AND claimed_job_id IS NOT NULL
        """
    )
    suspend fun countClaimedTurns(chatId: Int): Int

    @Query(
        """
        UPDATE memory_pending_turn
        SET claimed_job_id = :jobId, updated_at = :updatedAt
        WHERE claimed_job_id IS NULL AND turn_key IN (:turnKeys)
        """
    )
    suspend fun claimTurns(turnKeys: List<String>, jobId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE memory_pending_turn
        SET claimed_job_id = NULL, updated_at = :updatedAt
        WHERE claimed_job_id = :jobId
        """
    )
    suspend fun releaseClaim(jobId: String, updatedAt: Long): Int

    @Query("DELETE FROM memory_pending_turn WHERE claimed_job_id = :jobId")
    suspend fun deleteClaimedTurns(jobId: String): Int

    @Query("DELETE FROM memory_pending_turn")
    suspend fun deleteAllPendingTurns(): Int

    @Query(
        """
        UPDATE memory_chat_checkpoint
        SET last_processed_user_message_id = last_observed_user_message_id,
            pending_since = NULL,
            idle_due_at = NULL,
            updated_at = :updatedAt
        """
    )
    suspend fun advanceAllCheckpointsToObserved(updatedAt: Long): Int

    @Query(
        """
        SELECT checkpoint.* FROM memory_chat_checkpoint AS checkpoint
        WHERE checkpoint.idle_due_at IS NOT NULL
            AND checkpoint.idle_due_at <= :now
            AND EXISTS (
                SELECT 1 FROM memory_pending_turn AS pending
                WHERE pending.chat_id = checkpoint.chat_id
                    AND pending.claimed_job_id IS NULL
            )
        ORDER BY checkpoint.idle_due_at ASC, checkpoint.chat_id ASC
        LIMIT :limit
        """
    )
    suspend fun getDueIdleCheckpoints(now: Long, limit: Int): List<MemoryChatCheckpoint>

    @Query(
        """
        SELECT MIN(checkpoint.idle_due_at) FROM memory_chat_checkpoint AS checkpoint
        WHERE checkpoint.idle_due_at IS NOT NULL
            AND EXISTS (
                SELECT 1 FROM memory_pending_turn AS pending
                WHERE pending.chat_id = checkpoint.chat_id
                    AND pending.claimed_job_id IS NULL
            )
        """
    )
    suspend fun getEarliestIdleDueAt(): Long?

    @Query(
        """
        SELECT * FROM memory_pending_turn
        WHERE chat_id = :chatId
            AND user_message_id <= :lastUserMessageId
            AND claimed_job_id IS NULL
        ORDER BY completed_at ASC, user_message_id ASC
        """
    )
    suspend fun getUnclaimedTurnsThrough(chatId: Int, lastUserMessageId: Int): List<MemoryPendingTurn>

    @Transaction
    suspend fun claimOldestPendingTurns(
        chatId: Int,
        jobId: String,
        limit: Int,
        updatedAt: Long
    ): List<MemoryPendingTurn> {
        if (limit <= 0 || countClaimedTurns(chatId) > 0) return emptyList()

        val candidates = getOldestUnclaimedTurns(chatId, limit)
        if (candidates.isEmpty()) return emptyList()

        val claimedCount = claimTurns(candidates.map { it.turnKey }, jobId, updatedAt)
        check(claimedCount == candidates.size) { "Pending memory turns changed while creating a batch" }
        return getTurnsClaimedByJob(jobId)
    }

    @Transaction
    suspend fun completeClaimedBatch(jobId: String, updatedAt: Long): Boolean {
        val claimedTurns = getTurnsClaimedByJob(jobId)
        if (claimedTurns.isEmpty()) return false

        val chatId = claimedTurns.first().chatId
        check(claimedTurns.all { it.chatId == chatId }) { "A memory batch cannot span chats" }
        val checkpoint = checkNotNull(getCheckpoint(chatId)) { "Missing checkpoint for claimed memory batch" }
        val lastProcessedMessageId = claimedTurns.maxOf { it.userMessageId }

        deleteClaimedTurns(jobId)
        val oldestRemaining = getOldestUnclaimedTurns(chatId, 1).firstOrNull()
        upsertCheckpoint(
            checkpoint.copy(
                lastProcessedUserMessageId = maxOf(checkpoint.lastProcessedUserMessageId, lastProcessedMessageId),
                pendingSince = oldestRemaining?.completedAt,
                idleDueAt = checkpoint.idleDueAt.takeIf { oldestRemaining != null },
                updatedAt = updatedAt
            )
        )
        return true
    }

    @Transaction
    suspend fun clearPendingAndAdvanceBaselines(updatedAt: Long): Int {
        val deletedCount = deleteAllPendingTurns()
        advanceAllCheckpointsToObserved(updatedAt)
        return deletedCount
    }
}
