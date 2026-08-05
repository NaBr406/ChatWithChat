package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryBackfillCheckpointEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryEmbeddingCacheEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexQueueEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryVectorEntryEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryVectorSnapshotEntity

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_history_projection WHERE turn_key = :turnKey LIMIT 1")
    suspend fun findProjection(turnKey: String): ChatHistoryProjectionEntity?

    @Query("SELECT * FROM chat_history_projection WHERE chat_id = :chatId ORDER BY user_message_id")
    suspend fun findProjectionsForChat(chatId: Int): List<ChatHistoryProjectionEntity>

    @Query("SELECT * FROM chat_history_projection ORDER BY chat_id, user_message_id")
    suspend fun allProjections(): List<ChatHistoryProjectionEntity>

    @Query("SELECT * FROM chat_history_projection WHERE eligibility_state = :state ORDER BY updated_at DESC")
    suspend fun projectionsByState(state: String): List<ChatHistoryProjectionEntity>

    @RawQuery(observedEntities = [ChatHistoryProjectionEntity::class])
    suspend fun searchLexical(query: SupportSQLiteQuery): List<ChatHistoryProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProjection(projection: ChatHistoryProjectionEntity)

    @Update
    suspend fun updateProjection(projection: ChatHistoryProjectionEntity)

    @Transaction
    suspend fun upsertProjection(projection: ChatHistoryProjectionEntity) {
        val existing = findProjection(projection.turnKey)
        if (existing == null) {
            insertProjection(projection)
        } else {
            updateProjection(projection.copy(projectionId = existing.projectionId))
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQueue(queue: ChatHistoryIndexQueueEntity)

    @Query("DELETE FROM chat_history_index_queue WHERE turn_key = :turnKey")
    suspend fun deleteQueue(turnKey: String)

    @Query("SELECT * FROM chat_history_index_queue ORDER BY requested_at, turn_key LIMIT :limit")
    suspend fun nextQueue(limit: Int): List<ChatHistoryIndexQueueEntity>

    @Query("SELECT COUNT(*) FROM chat_history_index_queue")
    suspend fun queueCount(): Int

    @Query("UPDATE chat_history_index_queue SET attempt_count = attempt_count + 1 WHERE turn_key = :turnKey")
    suspend fun incrementAttempt(turnKey: String)

    @Query("SELECT * FROM chat_history_backfill_checkpoint WHERE checkpoint_id = :checkpointId LIMIT 1")
    suspend fun checkpoint(checkpointId: String): ChatHistoryBackfillCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: ChatHistoryBackfillCheckpointEntity)

    @Query("SELECT * FROM chat_history_index_state WHERE state_id = :stateId LIMIT 1")
    suspend fun indexState(stateId: String): ChatHistoryIndexStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIndexState(state: ChatHistoryIndexStateEntity)

    @Query("SELECT * FROM chat_history_embedding_cache WHERE turn_key = :turnKey AND content_hash = :contentHash AND descriptor_hash = :descriptorHash LIMIT 1")
    suspend fun findEmbedding(turnKey: String, contentHash: String, descriptorHash: String): ChatHistoryEmbeddingCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbedding(embedding: ChatHistoryEmbeddingCacheEntity)

    @Query("DELETE FROM chat_history_embedding_cache WHERE turn_key = :turnKey")
    suspend fun deleteEmbeddings(turnKey: String)

    @Query("DELETE FROM chat_history_embedding_cache WHERE turn_key IN (:turnKeys)")
    suspend fun deleteEmbeddings(turnKeys: List<String>)

    @Query("DELETE FROM chat_history_embedding_cache")
    suspend fun clearEmbeddings()

    @Query("SELECT * FROM chat_history_vector_snapshot WHERE snapshot_id = :snapshotId LIMIT 1")
    suspend fun vectorSnapshot(snapshotId: String): ChatHistoryVectorSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVectorSnapshot(snapshot: ChatHistoryVectorSnapshotEntity)

    @Query("DELETE FROM chat_history_vector_snapshot WHERE snapshot_id = :snapshotId")
    suspend fun deleteVectorSnapshot(snapshotId: String)

    @Query("SELECT * FROM chat_history_vector_entry WHERE snapshot_id = :snapshotId")
    suspend fun vectorEntries(snapshotId: String): List<ChatHistoryVectorEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVectorEntries(entries: List<ChatHistoryVectorEntryEntity>)

    @Query("DELETE FROM chat_history_vector_entry WHERE snapshot_id = :snapshotId")
    suspend fun deleteVectorEntries(snapshotId: String)

    @Query("DELETE FROM chat_history_vector_entry WHERE turn_key = :turnKey")
    suspend fun deleteVectorEntryForTurn(turnKey: String)

    @Query("DELETE FROM chat_history_vector_entry WHERE turn_key IN (:turnKeys)")
    suspend fun deleteVectorEntriesForTurns(turnKeys: List<String>)

    @Query("SELECT turn_key FROM chat_history_projection WHERE chat_id = :chatId")
    suspend fun turnKeysForChat(chatId: Int): List<String>

    @Delete
    suspend fun deleteProjection(projection: ChatHistoryProjectionEntity)

    @Query("DELETE FROM chat_history_projection WHERE turn_key = :turnKey")
    suspend fun deleteProjectionByKey(turnKey: String)

    @Query("UPDATE chat_history_projection SET eligibility_state = :state, updated_at = :updatedAt WHERE chat_id = :chatId")
    suspend fun markChatState(chatId: Int, state: String, updatedAt: Long)

    @Query("DELETE FROM chat_history_projection WHERE chat_id = :chatId")
    suspend fun deleteChatProjections(chatId: Int)

    @Query("DELETE FROM chat_history_index_queue WHERE chat_id = :chatId")
    suspend fun deleteChatQueue(chatId: Int)

    @Query("SELECT DISTINCT chat_id FROM chats_v2 ORDER BY chat_id LIMIT :limit")
    suspend fun firstChatIds(limit: Int): List<Int>

    @Query("SELECT DISTINCT chat_id FROM chats_v2 WHERE chat_id > :lastChatId ORDER BY chat_id LIMIT :limit")
    suspend fun chatIdsAfter(lastChatId: Int, limit: Int): List<Int>
}
