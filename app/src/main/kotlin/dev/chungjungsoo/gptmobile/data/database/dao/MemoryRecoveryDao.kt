package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryCorpusState
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationGroup
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationReceipt
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpusIndexStatus
import dev.chungjungsoo.gptmobile.data.memory.MemoryMutationState

@Dao
interface MemoryRecoveryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMutationGroup(group: MemoryMutationGroup)

    @Query("SELECT * FROM memory_mutation_group WHERE group_id = :groupId LIMIT 1")
    suspend fun getMutationGroup(groupId: String): MemoryMutationGroup?

    @Query("SELECT * FROM memory_mutation_group WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getMutationGroupByIdempotencyKey(idempotencyKey: String): MemoryMutationGroup?

    @Query(
        """
        SELECT * FROM memory_mutation_group
        WHERE semantic_job_id = :semanticJobId
        ORDER BY generation ASC, group_id ASC
        LIMIT 1
        """
    )
    suspend fun getMutationGroupBySemanticJobId(semanticJobId: String): MemoryMutationGroup?

    @Query(
        """
        SELECT * FROM memory_mutation_group
        WHERE state IN (:states)
        ORDER BY generation ASC, group_id ASC
        """
    )
    suspend fun getMutationGroupsByStates(states: List<String>): List<MemoryMutationGroup>

    @Query(
        """
        SELECT COALESCE(MAX(generation), 0) FROM (
            SELECT generation FROM memory_mutation_group
            UNION ALL
            SELECT generation FROM memory_corpus_state
        )
        """
    )
    suspend fun getLatestMutationGeneration(): Long

    @Query(
        """
        UPDATE memory_mutation_group
        SET state = :newState,
            last_error = :lastError,
            updated_at = :updatedAt,
            completed_at = :completedAt,
            row_version = row_version + 1
        WHERE group_id = :groupId
            AND generation = :expectedGeneration
            AND state = :expectedState
            AND row_version = :expectedRowVersion
        """
    )
    suspend fun transitionMutationGroupCas(
        groupId: String,
        expectedGeneration: Long,
        expectedState: String,
        expectedRowVersion: Long,
        newState: String,
        lastError: String?,
        updatedAt: Long,
        completedAt: Long?
    ): Int

    @Query(
        """
        UPDATE memory_mutation_group
        SET state = :newState,
            last_error = NULL,
            updated_at = :completedAt,
            completed_at = :completedAt,
            row_version = row_version + 1
        WHERE group_id = :groupId
            AND generation = :expectedGeneration
            AND state = :expectedState
            AND row_version = :expectedRowVersion
            AND expected_receipt_count > 0
            AND expected_receipt_count = (
                SELECT COUNT(*) FROM memory_mutation_receipt AS receipt_count
                WHERE receipt_count.group_id = :groupId
            )
            AND NOT EXISTS (
                SELECT 1 FROM memory_mutation_receipt AS incomplete_receipt
                WHERE incomplete_receipt.group_id = :groupId
                    AND (
                        incomplete_receipt.generation != :expectedGeneration
                        OR incomplete_receipt.state NOT IN (:committedReceiptStates)
                    )
            )
        """
    )
    suspend fun completeMutationGroupIfReceiptsCommitted(
        groupId: String,
        expectedGeneration: Long,
        expectedState: String,
        expectedRowVersion: Long,
        committedReceiptStates: List<String>,
        newState: String,
        completedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_mutation_group
        SET state = :newState,
            last_error = NULL,
            updated_at = :completedAt,
            completed_at = :completedAt,
            row_version = row_version + 1
        WHERE group_id = :groupId
            AND generation = :expectedGeneration
            AND state = :expectedState
            AND row_version = :expectedRowVersion
            AND expected_receipt_count = 0
            AND NOT EXISTS (
                SELECT 1 FROM memory_mutation_receipt AS unexpected_receipt
                WHERE unexpected_receipt.group_id = :groupId
            )
        """
    )
    suspend fun completeEmptyMutationGroupCas(
        groupId: String,
        expectedGeneration: Long,
        expectedState: String,
        expectedRowVersion: Long,
        newState: String,
        completedAt: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMutationReceipts(receipts: List<MemoryMutationReceipt>)

    @Query("SELECT * FROM memory_mutation_receipt WHERE receipt_id = :receiptId LIMIT 1")
    suspend fun getMutationReceipt(receiptId: String): MemoryMutationReceipt?

    @Query(
        """
        SELECT * FROM memory_mutation_receipt
        WHERE group_id = :groupId
        ORDER BY source_path ASC, receipt_id ASC
        """
    )
    suspend fun getMutationReceipts(groupId: String): List<MemoryMutationReceipt>

    @Query(
        """
        SELECT * FROM memory_mutation_receipt
        WHERE state IN (:states)
        ORDER BY generation ASC, group_id ASC, source_path ASC, receipt_id ASC
        """
    )
    suspend fun getMutationReceiptsByStates(states: List<String>): List<MemoryMutationReceipt>

    @Query(
        """
        UPDATE memory_mutation_receipt
        SET state = :newState,
            attempts = attempts + :attemptIncrement,
            last_error = :lastError,
            updated_at = :updatedAt,
            file_committed_at = COALESCE(file_committed_at, :fileCommittedAt),
            indexed_at = COALESCE(indexed_at, :indexedAt),
            row_version = row_version + 1
        WHERE receipt_id = :receiptId
            AND group_id = :groupId
            AND generation = :expectedGeneration
            AND state = :expectedState
            AND row_version = :expectedRowVersion
            AND target_source_hash = :expectedTargetSourceHash
            AND target_index_fingerprint IS :expectedTargetIndexFingerprint
            AND :attemptIncrement >= 0
        """
    )
    suspend fun transitionMutationReceiptCas(
        receiptId: String,
        groupId: String,
        expectedGeneration: Long,
        expectedState: String,
        expectedRowVersion: Long,
        expectedTargetSourceHash: String,
        expectedTargetIndexFingerprint: String?,
        newState: String,
        attemptIncrement: Int,
        lastError: String?,
        updatedAt: Long,
        fileCommittedAt: Long?,
        indexedAt: Long?
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCorpusStateIgnore(state: MemoryCorpusState): Long

    @Query("SELECT * FROM memory_corpus_state WHERE corpus = :corpus LIMIT 1")
    suspend fun getCorpusState(corpus: String): MemoryCorpusState?

    @Query("SELECT * FROM memory_corpus_state WHERE index_status IN (:statuses) ORDER BY updated_at ASC, corpus ASC")
    suspend fun getCorpusStatesByIndexStatuses(statuses: List<String>): List<MemoryCorpusState>

    @Query(
        """
        UPDATE memory_corpus_state
        SET source_path = :sourcePath,
            source_hash = :sourceHash,
            generation = :generation,
            target_index_fingerprint = :targetIndexFingerprint,
            index_status = :indexStatus,
            latest_receipt_id = :latestReceiptId,
            last_error = NULL,
            row_version = row_version + 1,
            updated_at = :updatedAt
        WHERE corpus = :corpus
            AND generation = :expectedGeneration
            AND source_hash = :expectedSourceHash
            AND row_version = :expectedRowVersion
            AND :generation > generation
        """
    )
    suspend fun advanceCorpusStateCas(
        corpus: String,
        expectedGeneration: Long,
        expectedSourceHash: String,
        expectedRowVersion: Long,
        sourcePath: String,
        sourceHash: String,
        generation: Long,
        targetIndexFingerprint: String?,
        indexStatus: String,
        latestReceiptId: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_corpus_state
        SET index_status = :newIndexStatus,
            last_error = :lastError,
            row_version = row_version + 1,
            updated_at = :updatedAt
        WHERE corpus = :corpus
            AND generation = :expectedGeneration
            AND source_hash = :expectedSourceHash
            AND target_index_fingerprint IS :expectedTargetIndexFingerprint
            AND index_status = :expectedIndexStatus
            AND row_version = :expectedRowVersion
        """
    )
    suspend fun transitionCorpusIndexStatusCas(
        corpus: String,
        expectedGeneration: Long,
        expectedSourceHash: String,
        expectedTargetIndexFingerprint: String?,
        expectedIndexStatus: String,
        expectedRowVersion: Long,
        newIndexStatus: String,
        lastError: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE memory_corpus_state
        SET index_status = :indexedStatus,
            indexed_generation = :expectedGeneration,
            indexed_source_hash = :expectedSourceHash,
            indexed_fingerprint = :expectedTargetIndexFingerprint,
            last_error = NULL,
            row_version = row_version + 1,
            updated_at = :updatedAt
        WHERE corpus = :corpus
            AND generation = :expectedGeneration
            AND source_hash = :expectedSourceHash
            AND target_index_fingerprint = :expectedTargetIndexFingerprint
            AND row_version = :expectedRowVersion
        """
    )
    suspend fun markCorpusIndexedCas(
        corpus: String,
        expectedGeneration: Long,
        expectedSourceHash: String,
        expectedTargetIndexFingerprint: String,
        expectedRowVersion: Long,
        indexedStatus: String,
        updatedAt: Long
    ): Int

    @Transaction
    suspend fun prepareMutation(request: MemoryMutationPrepareRequest): PreparedMemoryMutation {
        request.validate()
        getMutationGroup(request.groupId)?.let { existingGroup ->
            val existingReceipts = getMutationReceipts(existingGroup.groupId)
            validateIdempotentReplay(request, existingGroup, existingReceipts)
            return PreparedMemoryMutation(
                group = existingGroup,
                receipts = existingReceipts,
                isNew = false
            )
        }

        val generation = Math.addExact(getLatestMutationGeneration(), 1L)
        val group = MemoryMutationGroup(
            groupId = request.groupId,
            generation = generation,
            semanticJobId = request.semanticJobId,
            semanticBatchId = request.semanticBatchId,
            state = request.state,
            idempotencyKey = request.idempotencyKeyBase.withGeneration(generation),
            lastError = null,
            createdAt = request.createdAt,
            updatedAt = request.createdAt,
            completedAt = null,
            expectedReceiptCount = request.receipts.size
        )
        val receipts = request.receipts.map { draft ->
            MemoryMutationReceipt(
                receiptId = draft.receiptId,
                groupId = request.groupId,
                generation = generation,
                sourcePath = draft.sourcePath,
                baseSourceHash = draft.baseSourceHash,
                targetSourceHash = draft.targetSourceHash,
                stagedTargetPath = draft.stagedTargetPath,
                state = draft.state,
                idempotencyKey = draft.idempotencyKeyBase.withGeneration(generation, draft.receiptId),
                targetIndexFingerprint = draft.targetIndexFingerprint,
                attempts = 0,
                lastError = null,
                createdAt = request.createdAt,
                updatedAt = request.createdAt,
                fileCommittedAt = null,
                indexedAt = null
            )
        }

        insertMutationGroup(group)
        insertMutationReceipts(receipts)
        return PreparedMemoryMutation(
            group = group,
            receipts = receipts,
            isNew = true
        )
    }

    @Transaction
    suspend fun advanceCorpusGeneration(request: MemoryCorpusAdvanceRequest): MemoryCorpusAdvanceResult {
        request.validate()
        val current = getCorpusState(request.corpus)
        if (current == null) {
            val insertedState = request.toInitialState()
            val inserted = insertCorpusStateIgnore(insertedState)
            if (inserted != -1L) {
                return MemoryCorpusAdvanceResult(MemoryCorpusAdvanceOutcome.ADVANCED, insertedState)
            }
            return classifyCorpusReplay(request, checkNotNull(getCorpusState(request.corpus)))
        }
        if (request.generation <= current.generation) {
            return classifyCorpusReplay(request, current)
        }

        val advanced = advanceCorpusStateCas(
            corpus = request.corpus,
            expectedGeneration = current.generation,
            expectedSourceHash = current.sourceHash,
            expectedRowVersion = current.rowVersion,
            sourcePath = request.sourcePath,
            sourceHash = request.sourceHash,
            generation = request.generation,
            targetIndexFingerprint = request.targetIndexFingerprint,
            indexStatus = request.indexStatus,
            latestReceiptId = request.latestReceiptId,
            updatedAt = request.updatedAt
        )
        check(advanced == 1) { "Corpus state changed during its Room transaction" }
        return MemoryCorpusAdvanceResult(
            outcome = MemoryCorpusAdvanceOutcome.ADVANCED,
            state = checkNotNull(getCorpusState(request.corpus))
        )
    }

    @Transaction
    suspend fun completeVectorIndexPublication(
        request: MemoryVectorIndexPublicationRequest
    ): MemoryVectorIndexPublicationOutcome {
        request.validate()
        val corpus = getCorpusState(request.corpus)
            ?: return MemoryVectorIndexPublicationOutcome.CONFLICT
        if (corpus.generation > request.generation) {
            return MemoryVectorIndexPublicationOutcome.SUPERSEDED
        }
        if (!corpus.matches(request)) {
            return MemoryVectorIndexPublicationOutcome.CONFLICT
        }

        val receipt = getMutationReceipt(request.receiptId)
            ?: return MemoryVectorIndexPublicationOutcome.CONFLICT
        val group = getMutationGroup(request.mutationGroupId)
            ?: return MemoryVectorIndexPublicationOutcome.CONFLICT
        if (!receipt.matches(request) || !group.matches(request)) {
            return MemoryVectorIndexPublicationOutcome.CONFLICT
        }
        if (receipt.state == MemoryMutationState.SUPERSEDED) {
            return MemoryVectorIndexPublicationOutcome.SUPERSEDED
        }
        if (receipt.state !in setOf(MemoryMutationState.INDEX_PENDING, MemoryMutationState.INDEXED)) {
            return MemoryVectorIndexPublicationOutcome.CONFLICT
        }
        if (
            group.state !in setOf(
                MemoryMutationState.SEMANTIC_ACK_PENDING,
                MemoryMutationState.INDEX_PENDING,
                MemoryMutationState.INDEXED
            )
        ) {
            return MemoryVectorIndexPublicationOutcome.CONFLICT
        }

        val wasAlreadyComplete = corpus.isIndexedFor(request) && receipt.state == MemoryMutationState.INDEXED
        if (!corpus.isIndexedFor(request)) {
            check(
                markCorpusIndexedCas(
                    corpus = request.corpus,
                    expectedGeneration = request.generation,
                    expectedSourceHash = request.sourceHash,
                    expectedTargetIndexFingerprint = request.targetIndexFingerprint,
                    expectedRowVersion = corpus.rowVersion,
                    indexedStatus = MemoryCorpusIndexStatus.READY,
                    updatedAt = request.completedAt
                ) == 1
            ) { "Corpus state changed before vector publication could be recorded" }
        }

        if (receipt.state == MemoryMutationState.INDEX_PENDING) {
            check(
                transitionMutationReceiptCas(
                    receiptId = receipt.receiptId,
                    groupId = receipt.groupId,
                    expectedGeneration = receipt.generation,
                    expectedState = receipt.state,
                    expectedRowVersion = receipt.rowVersion,
                    expectedTargetSourceHash = receipt.targetSourceHash,
                    expectedTargetIndexFingerprint = receipt.targetIndexFingerprint,
                    newState = MemoryMutationState.INDEXED,
                    attemptIncrement = 0,
                    lastError = null,
                    updatedAt = request.completedAt,
                    fileCommittedAt = null,
                    indexedAt = request.completedAt
                ) == 1
            ) { "Mutation receipt changed before vector publication could be recorded" }
        }

        if (group.state == MemoryMutationState.INDEX_PENDING) {
            check(
                completeMutationGroupIfReceiptsCommitted(
                    groupId = group.groupId,
                    expectedGeneration = group.generation,
                    expectedState = group.state,
                    expectedRowVersion = group.rowVersion,
                    committedReceiptStates = listOf(MemoryMutationState.INDEXED),
                    newState = MemoryMutationState.INDEXED,
                    completedAt = request.completedAt
                ) == 1
            ) { "Mutation group changed before vector publication could be recorded" }
        }

        return if (wasAlreadyComplete) {
            MemoryVectorIndexPublicationOutcome.ALREADY_COMPLETE
        } else {
            MemoryVectorIndexPublicationOutcome.COMPLETED
        }
    }

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
        WHERE semantic_job_id = :semanticJobId
        ORDER BY daily_date ASC, daily_source_path ASC, batch_key ASC, checkpoint_id ASC
        LIMIT 1
        """
    )
    suspend fun getDistillationCheckpointBySemanticJobId(semanticJobId: String): MemoryDistillationCheckpoint?

    @Query(
        """
        SELECT * FROM memory_distillation_checkpoint
        WHERE status IN (:statuses)
        ORDER BY daily_date ASC, daily_source_path ASC
        """
    )
    suspend fun getDistillationCheckpointsByStatuses(statuses: List<String>): List<MemoryDistillationCheckpoint>

    @Query(
        """
        UPDATE memory_distillation_checkpoint
        SET status = :newStatus,
            target_source_hash = :newTargetSourceHash,
            mutation_group_id = COALESCE(:mutationGroupId, mutation_group_id),
            updated_at = :updatedAt,
            processed_at = COALESCE(processed_at, :processedAt),
            row_version = row_version + 1
        WHERE checkpoint_id = :checkpointId
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND daily_source_path = :expectedDailySourcePath
            AND daily_source_hash = :expectedDailySourceHash
            AND batch_key = :expectedBatchKey
            AND semantic_job_id = :expectedSemanticJobId
            AND target_source_path = :expectedTargetSourcePath
            AND target_base_hash = :expectedTargetBaseHash
            AND target_source_hash = :expectedTargetSourceHash
            AND mutation_group_id IS :expectedMutationGroupId
            AND :updatedAt >= updated_at
            AND (:processedAt IS NULL OR :processedAt >= 0)
        """
    )
    suspend fun transitionDistillationCheckpointCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedDailySourcePath: String,
        expectedDailySourceHash: String,
        expectedBatchKey: String,
        expectedSemanticJobId: String,
        expectedTargetSourcePath: String,
        expectedTargetBaseHash: String,
        expectedTargetSourceHash: String,
        expectedMutationGroupId: String?,
        newStatus: String,
        newTargetSourceHash: String,
        mutationGroupId: String?,
        updatedAt: Long,
        processedAt: Long?
    ): Int

    @Query(
        """
        UPDATE memory_distillation_checkpoint
        SET semantic_job_id = :newSemanticJobId,
            target_base_hash = :newTargetBaseHash,
            target_source_hash = :newTargetBaseHash,
            mutation_group_id = NULL,
            status = :newStatus,
            updated_at = :updatedAt,
            processed_at = NULL,
            row_version = row_version + 1
        WHERE checkpoint_id = :checkpointId
            AND status = :expectedStatus
            AND row_version = :expectedRowVersion
            AND daily_source_path = :expectedDailySourcePath
            AND daily_source_hash = :expectedDailySourceHash
            AND batch_key = :expectedBatchKey
            AND semantic_job_id = :expectedSemanticJobId
            AND target_source_path = :expectedTargetSourcePath
            AND target_base_hash = :expectedTargetBaseHash
            AND target_source_hash = :expectedTargetSourceHash
            AND mutation_group_id IS NULL
            AND processed_at IS NULL
            AND :updatedAt >= updated_at
        """
    )
    suspend fun replanDistillationCheckpointCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedDailySourcePath: String,
        expectedDailySourceHash: String,
        expectedBatchKey: String,
        expectedSemanticJobId: String,
        expectedTargetSourcePath: String,
        expectedTargetBaseHash: String,
        expectedTargetSourceHash: String,
        newSemanticJobId: String,
        newTargetBaseHash: String,
        newStatus: String,
        updatedAt: Long
    ): Int

    private fun MemoryMutationPrepareRequest.validate() {
        require(groupId.isNotBlank()) { "Mutation group ID must not be blank" }
        require(idempotencyKeyBase.isNotBlank()) { "Mutation group idempotency key must not be blank" }
        require(state.isNotBlank()) { "Mutation group state must not be blank" }
        require(createdAt >= 0) { "Mutation creation time must not be negative" }
        require(receipts.map { it.receiptId }.toSet().size == receipts.size) {
            "Mutation receipt IDs must be unique within a group"
        }
        require(receipts.map { it.sourcePath }.toSet().size == receipts.size) {
            "Mutation source paths must be unique within a group"
        }
        receipts.forEach { receipt -> receipt.validate() }
    }

    private fun MemoryMutationReceiptDraft.validate() {
        require(receiptId.isNotBlank()) { "Mutation receipt ID must not be blank" }
        require(sourcePath.isNotBlank()) { "Mutation source path must not be blank" }
        require(baseSourceHash.isNotBlank()) { "Mutation base hash must not be blank" }
        require(targetSourceHash.isNotBlank()) { "Mutation target hash must not be blank" }
        require(stagedTargetPath.isNotBlank()) { "Mutation staged path must not be blank" }
        require(state.isNotBlank()) { "Mutation receipt state must not be blank" }
        require(idempotencyKeyBase.isNotBlank()) { "Mutation receipt idempotency key must not be blank" }
    }

    private fun validateIdempotentReplay(
        request: MemoryMutationPrepareRequest,
        group: MemoryMutationGroup,
        receipts: List<MemoryMutationReceipt>
    ) {
        check(group.expectedReceiptCount == request.receipts.size && receipts.size == request.receipts.size) {
            "Existing mutation group has an incomplete receipt set"
        }
        check(group.semanticJobId == request.semanticJobId && group.semanticBatchId == request.semanticBatchId) {
            "Existing mutation group belongs to a different semantic source"
        }
        val expectedById = request.receipts.associateBy { it.receiptId }
        receipts.forEach { receipt ->
            val expected = checkNotNull(expectedById[receipt.receiptId]) {
                "Existing mutation group has an unexpected receipt"
            }
            check(
                receipt.sourcePath == expected.sourcePath &&
                    receipt.baseSourceHash == expected.baseSourceHash &&
                    receipt.targetSourceHash == expected.targetSourceHash &&
                    receipt.stagedTargetPath == expected.stagedTargetPath &&
                    receipt.targetIndexFingerprint == expected.targetIndexFingerprint
            ) { "Existing mutation receipt does not match the requested target" }
        }
    }

    private fun MemoryCorpusAdvanceRequest.validate() {
        require(corpus.isNotBlank()) { "Corpus must not be blank" }
        require(sourcePath.isNotBlank()) { "Corpus source path must not be blank" }
        require(sourceHash.isNotBlank()) { "Corpus source hash must not be blank" }
        require(generation > 0) { "Corpus generation must be positive" }
        require(indexStatus.isNotBlank()) { "Corpus index status must not be blank" }
        require(updatedAt >= 0) { "Corpus update time must not be negative" }
    }

    private fun MemoryVectorIndexPublicationRequest.validate() {
        require(corpus.isNotBlank()) { "Corpus must not be blank" }
        require(mutationGroupId.isNotBlank()) { "Mutation group ID must not be blank" }
        require(receiptId.isNotBlank()) { "Mutation receipt ID must not be blank" }
        require(sourcePath.isNotBlank()) { "Source path must not be blank" }
        require(sourceHash.isNotBlank()) { "Source hash must not be blank" }
        require(generation > 0) { "Corpus generation must be positive" }
        require(targetIndexFingerprint.isNotBlank()) { "Index fingerprint must not be blank" }
        require(completedAt >= 0) { "Index completion time must not be negative" }
    }

    private fun MemoryCorpusState.matches(request: MemoryVectorIndexPublicationRequest): Boolean =
        generation == request.generation &&
            sourcePath == request.sourcePath &&
            sourceHash == request.sourceHash &&
            targetIndexFingerprint == request.targetIndexFingerprint &&
            latestReceiptId == request.receiptId

    private fun MemoryCorpusState.isIndexedFor(request: MemoryVectorIndexPublicationRequest): Boolean =
        matches(request) &&
            indexStatus == MemoryCorpusIndexStatus.READY &&
            indexedGeneration == request.generation &&
            indexedSourceHash == request.sourceHash &&
            indexedFingerprint == request.targetIndexFingerprint

    private fun MemoryMutationReceipt.matches(request: MemoryVectorIndexPublicationRequest): Boolean =
        receiptId == request.receiptId &&
            groupId == request.mutationGroupId &&
            generation == request.generation &&
            sourcePath == request.sourcePath &&
            targetSourceHash == request.sourceHash &&
            targetIndexFingerprint == request.targetIndexFingerprint

    private fun MemoryMutationGroup.matches(request: MemoryVectorIndexPublicationRequest): Boolean =
        groupId == request.mutationGroupId && generation == request.generation

    private fun MemoryCorpusAdvanceRequest.toInitialState(): MemoryCorpusState = MemoryCorpusState(
        corpus = corpus,
        sourcePath = sourcePath,
        sourceHash = sourceHash,
        generation = generation,
        targetIndexFingerprint = targetIndexFingerprint,
        indexStatus = indexStatus,
        indexedGeneration = null,
        indexedSourceHash = null,
        indexedFingerprint = null,
        latestReceiptId = latestReceiptId,
        lastError = null,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun classifyCorpusReplay(
        request: MemoryCorpusAdvanceRequest,
        current: MemoryCorpusState
    ): MemoryCorpusAdvanceResult {
        if (request.generation < current.generation) {
            return MemoryCorpusAdvanceResult(MemoryCorpusAdvanceOutcome.STALE, current)
        }
        check(
            request.generation == current.generation &&
                request.sourcePath == current.sourcePath &&
                request.sourceHash == current.sourceHash &&
                request.targetIndexFingerprint == current.targetIndexFingerprint &&
                request.latestReceiptId == current.latestReceiptId
        ) { "The same corpus generation cannot identify different content" }
        return MemoryCorpusAdvanceResult(MemoryCorpusAdvanceOutcome.ALREADY_CURRENT, current)
    }

    private fun String.withGeneration(generation: Long, receiptId: String? = null): String = buildString {
        append(this@withGeneration)
        append("|generation=")
        append(generation)
        receiptId?.let {
            append("|receipt=")
            append(it)
        }
    }
}

data class MemoryMutationPrepareRequest(
    val groupId: String,
    val semanticJobId: String?,
    val semanticBatchId: String?,
    val state: String,
    val idempotencyKeyBase: String,
    val receipts: List<MemoryMutationReceiptDraft>,
    val createdAt: Long
)

data class MemoryMutationReceiptDraft(
    val receiptId: String,
    val sourcePath: String,
    val baseSourceHash: String,
    val targetSourceHash: String,
    val stagedTargetPath: String,
    val state: String,
    val idempotencyKeyBase: String,
    val targetIndexFingerprint: String?
)

data class PreparedMemoryMutation(
    val group: MemoryMutationGroup,
    val receipts: List<MemoryMutationReceipt>,
    val isNew: Boolean
)

data class MemoryCorpusAdvanceRequest(
    val corpus: String,
    val sourcePath: String,
    val sourceHash: String,
    val generation: Long,
    val targetIndexFingerprint: String?,
    val indexStatus: String,
    val latestReceiptId: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class MemoryCorpusAdvanceResult(
    val outcome: MemoryCorpusAdvanceOutcome,
    val state: MemoryCorpusState
)

enum class MemoryCorpusAdvanceOutcome {
    ADVANCED,
    ALREADY_CURRENT,
    STALE
}

data class MemoryVectorIndexPublicationRequest(
    val corpus: String,
    val mutationGroupId: String,
    val receiptId: String,
    val generation: Long,
    val sourcePath: String,
    val sourceHash: String,
    val targetIndexFingerprint: String,
    val completedAt: Long
)

enum class MemoryVectorIndexPublicationOutcome {
    COMPLETED,
    ALREADY_COMPLETE,
    SUPERSEDED,
    CONFLICT
}
