package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryCorpusState
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationGroup
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationReceipt

internal class InMemoryMemoryRecoveryDao(
    var failNextReceiptTransition: Boolean = false
) : MemoryRecoveryDao {
    private val mutationGroups = linkedMapOf<String, MemoryMutationGroup>()
    private val mutationReceipts = linkedMapOf<String, MemoryMutationReceipt>()
    private val corpusStates = linkedMapOf<String, MemoryCorpusState>()
    private val distillationCheckpoints = linkedMapOf<String, MemoryDistillationCheckpoint>()

    override suspend fun insertMutationGroup(group: MemoryMutationGroup) {
        check(group.groupId !in mutationGroups) { "Duplicate mutation group ID" }
        check(mutationGroups.values.none { it.idempotencyKey == group.idempotencyKey }) {
            "Duplicate mutation group idempotency key"
        }
        mutationGroups[group.groupId] = group
    }

    override suspend fun getMutationGroup(groupId: String): MemoryMutationGroup? =
        mutationGroups[groupId]

    override suspend fun getMutationGroupByIdempotencyKey(idempotencyKey: String): MemoryMutationGroup? =
        mutationGroups.values.firstOrNull { group -> group.idempotencyKey == idempotencyKey }

    override suspend fun getMutationGroupBySemanticJobId(semanticJobId: String): MemoryMutationGroup? =
        mutationGroups.values
            .filter { group -> group.semanticJobId == semanticJobId }
            .minWithOrNull(compareBy<MemoryMutationGroup> { it.generation }.thenBy { it.groupId })

    override suspend fun getMutationGroupsByStates(states: List<String>): List<MemoryMutationGroup> =
        mutationGroups.values
            .filter { group -> group.state in states }
            .sortedWith(compareBy<MemoryMutationGroup> { it.generation }.thenBy { it.groupId })

    override suspend fun getLatestMutationGeneration(): Long = maxOf(
        mutationGroups.values.maxOfOrNull { group -> group.generation } ?: 0L,
        corpusStates.values.maxOfOrNull { state -> state.generation } ?: 0L
    )

    override suspend fun transitionMutationGroupCas(
        groupId: String,
        expectedGeneration: Long,
        expectedState: String,
        expectedRowVersion: Long,
        newState: String,
        lastError: String?,
        updatedAt: Long,
        completedAt: Long?
    ): Int {
        val current = mutationGroups[groupId] ?: return 0
        if (
            current.generation != expectedGeneration ||
            current.state != expectedState ||
            current.rowVersion != expectedRowVersion
        ) {
            return 0
        }
        mutationGroups[groupId] = current.copy(
            state = newState,
            lastError = lastError,
            updatedAt = updatedAt,
            completedAt = completedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun completeMutationGroupIfReceiptsCommitted(
        groupId: String,
        expectedGeneration: Long,
        expectedState: String,
        expectedRowVersion: Long,
        committedReceiptStates: List<String>,
        newState: String,
        completedAt: Long
    ): Int {
        val current = mutationGroups[groupId] ?: return 0
        val receipts = mutationReceipts.values.filter { receipt -> receipt.groupId == groupId }
        val matches = current.generation == expectedGeneration &&
            current.state == expectedState &&
            current.rowVersion == expectedRowVersion &&
            current.expectedReceiptCount > 0 &&
            receipts.size == current.expectedReceiptCount &&
            receipts.all { receipt ->
                receipt.generation == expectedGeneration && receipt.state in committedReceiptStates
            }
        if (!matches) return 0
        mutationGroups[groupId] = current.copy(
            state = newState,
            lastError = null,
            updatedAt = completedAt,
            completedAt = completedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun completeEmptyMutationGroupCas(
        groupId: String,
        expectedGeneration: Long,
        expectedState: String,
        expectedRowVersion: Long,
        newState: String,
        completedAt: Long
    ): Int {
        val current = mutationGroups[groupId] ?: return 0
        val matches = current.generation == expectedGeneration &&
            current.state == expectedState &&
            current.rowVersion == expectedRowVersion &&
            current.expectedReceiptCount == 0 &&
            mutationReceipts.values.none { receipt -> receipt.groupId == groupId }
        if (!matches) return 0
        mutationGroups[groupId] = current.copy(
            state = newState,
            lastError = null,
            updatedAt = completedAt,
            completedAt = completedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun insertMutationReceipts(receipts: List<MemoryMutationReceipt>) {
        check(receipts.map { it.receiptId }.distinct().size == receipts.size) {
            "Duplicate mutation receipt ID in insert"
        }
        check(receipts.map { it.idempotencyKey }.distinct().size == receipts.size) {
            "Duplicate mutation receipt idempotency key in insert"
        }
        check(receipts.map { it.groupId to it.sourcePath }.distinct().size == receipts.size) {
            "Duplicate mutation source path in group"
        }
        receipts.forEach { receipt ->
            check(receipt.groupId in mutationGroups) { "Missing mutation group" }
            check(receipt.receiptId !in mutationReceipts) { "Duplicate mutation receipt ID" }
            check(mutationReceipts.values.none { it.idempotencyKey == receipt.idempotencyKey }) {
                "Duplicate mutation receipt idempotency key"
            }
            check(
                mutationReceipts.values.none { existing ->
                    existing.groupId == receipt.groupId && existing.sourcePath == receipt.sourcePath
                }
            ) { "Duplicate mutation source path in group" }
        }
        receipts.forEach { receipt -> mutationReceipts[receipt.receiptId] = receipt }
    }

    override suspend fun getMutationReceipt(receiptId: String): MemoryMutationReceipt? =
        mutationReceipts[receiptId]

    override suspend fun getMutationReceipts(groupId: String): List<MemoryMutationReceipt> =
        mutationReceipts.values
            .filter { receipt -> receipt.groupId == groupId }
            .sortedWith(compareBy<MemoryMutationReceipt> { it.sourcePath }.thenBy { it.receiptId })

    override suspend fun getMutationReceiptsByStates(states: List<String>): List<MemoryMutationReceipt> =
        mutationReceipts.values
            .filter { receipt -> receipt.state in states }
            .sortedWith(
                compareBy<MemoryMutationReceipt> { it.generation }
                    .thenBy { it.groupId }
                    .thenBy { it.sourcePath }
                    .thenBy { it.receiptId }
            )

    override suspend fun transitionMutationReceiptCas(
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
    ): Int {
        if (failNextReceiptTransition) {
            failNextReceiptTransition = false
            error("injected_receipt_transition_failure")
        }
        if (attemptIncrement < 0) return 0
        val current = mutationReceipts[receiptId] ?: return 0
        val matches = current.groupId == groupId &&
            current.generation == expectedGeneration &&
            current.state == expectedState &&
            current.rowVersion == expectedRowVersion &&
            current.targetSourceHash == expectedTargetSourceHash &&
            current.targetIndexFingerprint == expectedTargetIndexFingerprint
        if (!matches) return 0
        mutationReceipts[receiptId] = current.copy(
            state = newState,
            attempts = current.attempts + attemptIncrement,
            lastError = lastError,
            updatedAt = updatedAt,
            fileCommittedAt = current.fileCommittedAt ?: fileCommittedAt,
            indexedAt = current.indexedAt ?: indexedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun insertCorpusStateIgnore(state: MemoryCorpusState): Long {
        if (state.corpus in corpusStates) return -1L
        corpusStates[state.corpus] = state
        return corpusStates.size.toLong()
    }

    override suspend fun getCorpusState(corpus: String): MemoryCorpusState? = corpusStates[corpus]

    override suspend fun getCorpusStatesByIndexStatuses(statuses: List<String>): List<MemoryCorpusState> =
        corpusStates.values
            .filter { state -> state.indexStatus in statuses }
            .sortedWith(compareBy<MemoryCorpusState> { it.updatedAt }.thenBy { it.corpus })

    override suspend fun advanceCorpusStateCas(
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
    ): Int {
        val current = corpusStates[corpus] ?: return 0
        val matches = current.generation == expectedGeneration &&
            current.sourceHash == expectedSourceHash &&
            current.rowVersion == expectedRowVersion &&
            generation > current.generation
        if (!matches) return 0
        corpusStates[corpus] = current.copy(
            sourcePath = sourcePath,
            sourceHash = sourceHash,
            generation = generation,
            targetIndexFingerprint = targetIndexFingerprint,
            indexStatus = indexStatus,
            latestReceiptId = latestReceiptId,
            lastError = null,
            rowVersion = current.rowVersion + 1,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun transitionCorpusIndexStatusCas(
        corpus: String,
        expectedGeneration: Long,
        expectedSourceHash: String,
        expectedTargetIndexFingerprint: String?,
        expectedIndexStatus: String,
        expectedRowVersion: Long,
        newIndexStatus: String,
        lastError: String?,
        updatedAt: Long
    ): Int {
        val current = corpusStates[corpus] ?: return 0
        val matches = current.generation == expectedGeneration &&
            current.sourceHash == expectedSourceHash &&
            current.targetIndexFingerprint == expectedTargetIndexFingerprint &&
            current.indexStatus == expectedIndexStatus &&
            current.rowVersion == expectedRowVersion
        if (!matches) return 0
        corpusStates[corpus] = current.copy(
            indexStatus = newIndexStatus,
            lastError = lastError,
            rowVersion = current.rowVersion + 1,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun markCorpusIndexedCas(
        corpus: String,
        expectedGeneration: Long,
        expectedSourceHash: String,
        expectedTargetIndexFingerprint: String,
        expectedRowVersion: Long,
        indexedStatus: String,
        updatedAt: Long
    ): Int {
        val current = corpusStates[corpus] ?: return 0
        val matches = current.generation == expectedGeneration &&
            current.sourceHash == expectedSourceHash &&
            current.targetIndexFingerprint == expectedTargetIndexFingerprint &&
            current.rowVersion == expectedRowVersion
        if (!matches) return 0
        corpusStates[corpus] = current.copy(
            indexStatus = indexedStatus,
            indexedGeneration = expectedGeneration,
            indexedSourceHash = expectedSourceHash,
            indexedFingerprint = expectedTargetIndexFingerprint,
            lastError = null,
            rowVersion = current.rowVersion + 1,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun insertDistillationCheckpointIgnore(checkpoint: MemoryDistillationCheckpoint): Long {
        val duplicate = distillationCheckpoints.values.any { existing ->
            existing.dailySourcePath == checkpoint.dailySourcePath &&
                existing.dailySourceHash == checkpoint.dailySourceHash &&
                existing.batchKey == checkpoint.batchKey
        }
        if (checkpoint.checkpointId in distillationCheckpoints || duplicate) return -1L
        distillationCheckpoints[checkpoint.checkpointId] = checkpoint
        return distillationCheckpoints.size.toLong()
    }

    override suspend fun getDistillationCheckpoint(
        dailySourcePath: String,
        dailySourceHash: String,
        batchKey: String
    ): MemoryDistillationCheckpoint? = distillationCheckpoints.values.firstOrNull { checkpoint ->
        checkpoint.dailySourcePath == dailySourcePath &&
            checkpoint.dailySourceHash == dailySourceHash &&
            checkpoint.batchKey == batchKey
    }

    override suspend fun getDistillationCheckpointBySemanticJobId(
        semanticJobId: String
    ): MemoryDistillationCheckpoint? = distillationCheckpoints.values
        .asSequence()
        .filter { checkpoint -> checkpoint.semanticJobId == semanticJobId }
        .sortedWith(
            compareBy<MemoryDistillationCheckpoint> { checkpoint -> checkpoint.dailyDate }
                .thenBy { checkpoint -> checkpoint.dailySourcePath }
                .thenBy { checkpoint -> checkpoint.batchKey }
                .thenBy { checkpoint -> checkpoint.checkpointId }
        )
        .firstOrNull()

    override suspend fun getDistillationCheckpointsByStatuses(
        statuses: List<String>
    ): List<MemoryDistillationCheckpoint> = distillationCheckpoints.values
        .filter { checkpoint -> checkpoint.status in statuses }
        .sortedWith(
            compareBy<MemoryDistillationCheckpoint> { it.dailyDate }
                .thenBy { it.dailySourcePath }
        )

    override suspend fun transitionDistillationCheckpointCas(
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
    ): Int {
        val current = distillationCheckpoints[checkpointId] ?: return 0
        val matches = current.status == expectedStatus &&
            current.rowVersion == expectedRowVersion &&
            current.dailySourcePath == expectedDailySourcePath &&
            current.dailySourceHash == expectedDailySourceHash &&
            current.batchKey == expectedBatchKey &&
            current.semanticJobId == expectedSemanticJobId &&
            current.targetSourcePath == expectedTargetSourcePath &&
            current.targetBaseHash == expectedTargetBaseHash &&
            current.targetSourceHash == expectedTargetSourceHash &&
            current.mutationGroupId == expectedMutationGroupId &&
            updatedAt >= current.updatedAt &&
            (processedAt == null || processedAt >= 0)
        if (!matches) return 0
        distillationCheckpoints[checkpointId] = current.copy(
            targetSourceHash = newTargetSourceHash,
            mutationGroupId = mutationGroupId ?: current.mutationGroupId,
            status = newStatus,
            updatedAt = updatedAt,
            processedAt = current.processedAt ?: processedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun replanDistillationCheckpointCas(
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
    ): Int {
        val current = distillationCheckpoints[checkpointId] ?: return 0
        val matches = current.status == expectedStatus &&
            current.rowVersion == expectedRowVersion &&
            current.dailySourcePath == expectedDailySourcePath &&
            current.dailySourceHash == expectedDailySourceHash &&
            current.batchKey == expectedBatchKey &&
            current.semanticJobId == expectedSemanticJobId &&
            current.targetSourcePath == expectedTargetSourcePath &&
            current.targetBaseHash == expectedTargetBaseHash &&
            current.targetSourceHash == expectedTargetSourceHash &&
            current.mutationGroupId == null &&
            current.processedAt == null &&
            updatedAt >= current.updatedAt
        if (!matches) return 0
        distillationCheckpoints[checkpointId] = current.copy(
            semanticJobId = newSemanticJobId,
            targetBaseHash = newTargetBaseHash,
            targetSourceHash = newTargetBaseHash,
            mutationGroupId = null,
            status = newStatus,
            updatedAt = updatedAt,
            processedAt = null,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }
}
