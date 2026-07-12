package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryCorpusState
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationGroup
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationReceipt

@Dao
interface MemoryRecoveryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMutationGroupIgnore(group: MemoryMutationGroup): Long

    @Query("SELECT * FROM memory_mutation_group WHERE group_id = :groupId LIMIT 1")
    suspend fun getMutationGroup(groupId: String): MemoryMutationGroup?

    @Query("SELECT * FROM memory_mutation_group WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getMutationGroupByIdempotencyKey(idempotencyKey: String): MemoryMutationGroup?

    @Update
    suspend fun updateMutationGroup(group: MemoryMutationGroup)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMutationReceipts(receipts: List<MemoryMutationReceipt>)

    @Query("SELECT * FROM memory_mutation_receipt WHERE receipt_id = :receiptId LIMIT 1")
    suspend fun getMutationReceipt(receiptId: String): MemoryMutationReceipt?

    @Query(
        """
        SELECT * FROM memory_mutation_receipt
        WHERE group_id = :groupId
        ORDER BY source_path ASC
        """
    )
    suspend fun getMutationReceipts(groupId: String): List<MemoryMutationReceipt>

    @Query(
        """
        SELECT * FROM memory_mutation_receipt
        WHERE state IN (:states)
        ORDER BY created_at ASC, receipt_id ASC
        """
    )
    suspend fun getMutationReceiptsByStates(states: List<String>): List<MemoryMutationReceipt>

    @Update
    suspend fun updateMutationReceipt(receipt: MemoryMutationReceipt)

    @Upsert
    suspend fun upsertCorpusState(state: MemoryCorpusState)

    @Query("SELECT * FROM memory_corpus_state WHERE corpus = :corpus LIMIT 1")
    suspend fun getCorpusState(corpus: String): MemoryCorpusState?

    @Query("SELECT * FROM memory_corpus_state WHERE index_status IN (:statuses) ORDER BY updated_at ASC")
    suspend fun getCorpusStatesByIndexStatuses(statuses: List<String>): List<MemoryCorpusState>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDistillationCheckpointIgnore(checkpoint: MemoryDistillationCheckpoint): Long

    @Query(
        """
        SELECT * FROM memory_distillation_checkpoint
        WHERE daily_source_path = :dailySourcePath
            AND daily_source_hash = :dailySourceHash
            AND batch_key = :batchKey
        LIMIT 1
        """
    )
    suspend fun getDistillationCheckpoint(
        dailySourcePath: String,
        dailySourceHash: String,
        batchKey: String
    ): MemoryDistillationCheckpoint?

    @Query(
        """
        SELECT * FROM memory_distillation_checkpoint
        WHERE status IN (:statuses)
        ORDER BY daily_date ASC, daily_source_path ASC
        """
    )
    suspend fun getDistillationCheckpointsByStatuses(statuses: List<String>): List<MemoryDistillationCheckpoint>

    @Update
    suspend fun updateDistillationCheckpoint(checkpoint: MemoryDistillationCheckpoint)
}
