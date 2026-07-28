package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.nabr.chatwithchat.data.database.entity.MemoryLongTermConsolidationCheckpoint

@Dao
interface MemoryLongTermConsolidationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(checkpoint: MemoryLongTermConsolidationCheckpoint): Long

    @Query("SELECT * FROM memory_long_term_consolidation_checkpoint WHERE checkpoint_id = :checkpointId LIMIT 1")
    suspend fun getById(checkpointId: String): MemoryLongTermConsolidationCheckpoint?

    @Query("SELECT * FROM memory_long_term_consolidation_checkpoint WHERE job_id = :jobId LIMIT 1")
    suspend fun getByJobId(jobId: String): MemoryLongTermConsolidationCheckpoint?

    @Query(
        """
        SELECT * FROM memory_long_term_consolidation_checkpoint
        WHERE active_key = :activeKey
            AND status IN (:statuses)
        ORDER BY created_at ASC, checkpoint_id ASC
        LIMIT 1
        """
    )
    suspend fun getActive(
        activeKey: String,
        statuses: List<String>
    ): MemoryLongTermConsolidationCheckpoint?

    @Query(
        """
        SELECT * FROM memory_long_term_consolidation_checkpoint
        WHERE status = :completedStatus
        ORDER BY completed_at DESC, checkpoint_id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestCompleted(completedStatus: String): MemoryLongTermConsolidationCheckpoint?

    @Query(
        """
        SELECT COALESCE(SUM(material_mutation_count), 0)
        FROM memory_mutation_receipt
        WHERE source_path = :sourcePath
            AND generation > :afterGeneration
            AND file_committed_at IS NOT NULL
        """
    )
    suspend fun sumMaterialMutationsAfterGeneration(
        sourcePath: String,
        afterGeneration: Long
    ): Long

    @Query(
        """
        SELECT COALESCE(MAX(generation), 0)
        FROM memory_mutation_receipt
        WHERE source_path = :sourcePath
            AND file_committed_at IS NOT NULL
        """
    )
    suspend fun getLatestCommittedGeneration(sourcePath: String): Long

    @Query(
        """
        UPDATE memory_long_term_consolidation_checkpoint
        SET partition_cursor = :newPartitionCursor,
            proposal_hash = :newProposalHash,
            proposal_json = :newProposalJson,
            updated_at = :updatedAt,
            row_version = row_version + 1
        WHERE checkpoint_id = :checkpointId
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND base_source_hash = :expectedBaseSourceHash
            AND ordered_snapshot_hash = :expectedOrderedSnapshotHash
            AND partition_cursor = :expectedPartitionCursor
            AND proposal_hash IS :expectedProposalHash
            AND proposal_json IS :expectedProposalJson
            AND :newPartitionCursor >= partition_cursor
            AND :newPartitionCursor <= entry_count
            AND :updatedAt >= updated_at
        """
    )
    suspend fun advancePartitionCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedBaseSourceHash: String,
        expectedOrderedSnapshotHash: String,
        expectedPartitionCursor: Int,
        expectedProposalHash: String?,
        expectedProposalJson: String?,
        newPartitionCursor: Int,
        newProposalHash: String?,
        newProposalJson: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_long_term_consolidation_checkpoint
        SET continuation_required = :continuationRequired,
            updated_at = :updatedAt,
            row_version = row_version + 1
        WHERE checkpoint_id = :checkpointId
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND continuation_required = :expectedContinuationRequired
            AND :updatedAt >= updated_at
        """
    )
    suspend fun setContinuationRequiredCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedContinuationRequired: Boolean,
        continuationRequired: Boolean,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_long_term_consolidation_checkpoint
        SET resolved_platform_uid = :platformUid,
            resolved_model_id = :modelId,
            resolved_at = :resolvedAt,
            updated_at = :updatedAt,
            row_version = row_version + 1
        WHERE checkpoint_id = :checkpointId
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND resolved_platform_uid IS NULL
            AND resolved_model_id IS NULL
            AND resolved_at IS NULL
            AND :updatedAt >= updated_at
        """
    )
    suspend fun bindResolvedModelCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        platformUid: String,
        modelId: String,
        resolvedAt: Long,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_long_term_consolidation_checkpoint
        SET attempt = :attempt,
            last_error = NULL,
            updated_at = :updatedAt,
            row_version = row_version + 1
        WHERE checkpoint_id = :checkpointId
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND :updatedAt >= updated_at
        """
    )
    suspend fun recordAttemptCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        attempt: Int,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_long_term_consolidation_checkpoint
        SET last_error = :lastError,
            updated_at = :updatedAt,
            row_version = row_version + 1
        WHERE checkpoint_id = :checkpointId
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND :updatedAt >= updated_at
        """
    )
    suspend fun recordErrorCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        lastError: String,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_long_term_consolidation_checkpoint
        SET status = :newStatus,
            active_key = :newActiveKey,
            result_source_hash = :newResultSourceHash,
            completed_generation = :newCompletedGeneration,
            mutation_group_id = :newMutationGroupId,
            last_error = :lastError,
            completed_at = :completedAt,
            updated_at = :updatedAt,
            row_version = row_version + 1
        WHERE checkpoint_id = :checkpointId
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND result_source_hash = :expectedResultSourceHash
            AND mutation_group_id IS :expectedMutationGroupId
            AND :updatedAt >= updated_at
        """
    )
    suspend fun transitionCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedResultSourceHash: String,
        expectedMutationGroupId: String?,
        newStatus: String,
        newActiveKey: String?,
        newResultSourceHash: String,
        newCompletedGeneration: Long?,
        newMutationGroupId: String?,
        lastError: String?,
        completedAt: Long?,
        updatedAt: Long
    ): Int
}
