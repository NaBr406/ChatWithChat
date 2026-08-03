package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryBackfillCheckpointEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryEmbeddingEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexQueueEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_history_projection WHERE turn_key = :turnKey LIMIT 1")
    suspend fun getProjection(turnKey: String): ChatHistoryProjectionEntity?

    @Query("SELECT * FROM chat_history_projection WHERE chat_id = :chatId ORDER BY user_message_id")
    suspend fun getProjectionsForChat(chatId: Int): List<ChatHistoryProjectionEntity>

    @Query("SELECT * FROM chat_history_projection WHERE eligibility_state = 'eligible' ORDER BY turn_key")
    suspend fun getEligibleProjections(): List<ChatHistoryProjectionEntity>

    @Query("SELECT * FROM chat_history_projection WHERE turn_key IN (:turnKeys) AND eligibility_state = 'eligible'")
    suspend fun getProjectionsByTurnKeys(turnKeys: List<String>): List<ChatHistoryProjectionEntity>

    @Query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chat_history_projection_fts' LIMIT 1")
    suspend fun getFtsDefinition(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjection(projection: ChatHistoryProjectionEntity): Long

    @androidx.room.Update
    suspend fun updateProjection(projection: ChatHistoryProjectionEntity)

    @Query("DELETE FROM chat_history_projection WHERE turn_key = :turnKey")
    suspend fun deleteProjection(turnKey: String): Int

    @Query("DELETE FROM chat_history_projection")
    suspend fun deleteAllProjections(): Int

    @Query("UPDATE chat_history_projection SET eligibility_state = 'stale' WHERE chat_id = :chatId AND eligibility_state = 'eligible'")
    suspend fun markProjectionsStaleForChat(chatId: Int): Int

    @Query("UPDATE chat_history_projection SET eligibility_state = 'stale' WHERE eligibility_state = 'eligible'")
    suspend fun markAllProjectionsStale(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: ChatHistoryIndexQueueEntity)

    @Query("SELECT * FROM chat_history_index_queue ORDER BY requested_at, turn_key LIMIT :limit")
    suspend fun getQueueBatch(limit: Int): List<ChatHistoryIndexQueueEntity>

    @Query("SELECT COUNT(*) FROM chat_history_index_queue")
    suspend fun countQueue(): Int

    @Query("DELETE FROM chat_history_index_queue WHERE turn_key = :turnKey")
    suspend fun acknowledge(turnKey: String)

    @Query("UPDATE chat_history_index_queue SET attempt_count = attempt_count + 1, requested_at = :requestedAt WHERE turn_key = :turnKey")
    suspend fun recordQueueAttempt(turnKey: String, requestedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBackfillCheckpoint(checkpoint: ChatHistoryBackfillCheckpointEntity)

    @Query("SELECT * FROM chat_history_backfill_checkpoint WHERE checkpoint_id = :checkpointId LIMIT 1")
    suspend fun getBackfillCheckpoint(checkpointId: String): ChatHistoryBackfillCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIndexState(state: ChatHistoryIndexStateEntity)

    @Query("SELECT * FROM chat_history_index_state WHERE state_id = :stateId LIMIT 1")
    suspend fun getIndexState(stateId: String): ChatHistoryIndexStateEntity?

    @RawQuery(observedEntities = [ChatHistoryProjectionEntity::class])
    suspend fun searchLexical(query: SupportSQLiteQuery): List<ChatHistoryLexicalHit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbeddings(embeddings: List<ChatHistoryEmbeddingEntity>)

    @Query("SELECT * FROM chat_history_embedding_cache WHERE descriptor_hash = :descriptorHash ORDER BY turn_key")
    suspend fun getEmbeddings(descriptorHash: String): List<ChatHistoryEmbeddingEntity>

    @Query("DELETE FROM chat_history_embedding_cache WHERE turn_key NOT IN (SELECT turn_key FROM chat_history_projection WHERE eligibility_state = 'eligible')")
    suspend fun deleteOrphanedEmbeddings(): Int

    @Query("DELETE FROM chat_history_embedding_cache WHERE turn_key IN (:turnKeys)")
    suspend fun deleteEmbeddings(turnKeys: List<String>): Int

    @Query("DELETE FROM chat_history_embedding_cache")
    suspend fun deleteAllEmbeddings(): Int

    @Query("DELETE FROM chat_history_index_queue")
    suspend fun clearQueue(): Int
}

data class ChatHistoryLexicalHit(
    val projectionId: Long,
    val turnKey: String,
    val chatId: Int,
    val userMessageId: Int,
    val assistantMessageId: Int,
    val assistantPlatformUid: String,
    val title: String,
    val userContent: String,
    val assistantContent: String,
    val searchTerms: String,
    val contentHash: String,
    val projectionVersion: Int,
    val eligibilityState: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lexicalScore: Double
)
