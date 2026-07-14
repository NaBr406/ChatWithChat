package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryCorpusAdvanceOutcome
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryCorpusAdvanceRequest
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMutationConflictRequest
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMutationPrepareRequest
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMutationReceiptDraft
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationGroup
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationReceipt
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryMutationCoordinator(
    private val recoveryDao: MemoryRecoveryDao,
    private val memoryFileStore: MemoryFileStore,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json { encodeDefaults = true }
) {
    private val localMutationPrepareMutex = Mutex()

    suspend fun findBySemanticJobId(semanticJobId: String): MemoryPreparedMutation? =
        recoveryDao.getMutationGroupBySemanticJobId(semanticJobId)?.let { group -> loadMutation(group) }

    suspend fun prepare(
        semanticJobId: String,
        semanticBatchId: String,
        targets: List<MemoryMutationTarget>
    ): MemoryPreparedMutation {
        require(semanticJobId.isNotBlank()) { "Semantic job ID must not be blank" }
        require(semanticBatchId.isNotBlank()) { "Semantic batch ID must not be blank" }
        require(targets.map { target -> target.sourcePath }.distinct().size == targets.size) {
            "A prepared mutation cannot target one source path twice"
        }
        findBySemanticJobId(semanticJobId)?.let { return it }

        val groupId = "mutation_${"$semanticJobId|$semanticBatchId".sha256Utf8().take(ID_HASH_LENGTH)}"
        memoryFileStore.cleanupStagedTargets(groupId).getOrThrow()
        val stagedTargets = targets.sortedBy(MemoryMutationTarget::sourcePath).map { target ->
            val receiptId = receiptId(groupId, target.sourcePath)
            val expectedBaseHash = target.baseContent.toByteArray(Charsets.UTF_8).sha256Hex()
            val staged = memoryFileStore.stageMemoryFile(
                sourcePath = target.sourcePath,
                content = target.targetContent,
                stagingId = receiptId
            ).getOrThrow()
            check(staged.baseSourceHash == expectedBaseHash) {
                "Memory source changed before its mutation receipt was prepared"
            }
            target to staged
        }
        val prepared = recoveryDao.prepareMutation(
            MemoryMutationPrepareRequest(
                groupId = groupId,
                semanticJobId = semanticJobId,
                semanticBatchId = semanticBatchId,
                state = MemoryMutationState.PREPARED,
                idempotencyKeyBase = "memory-mutation:$semanticJobId:$semanticBatchId",
                receipts = stagedTargets.map { (target, staged) ->
                    MemoryMutationReceiptDraft(
                        receiptId = receiptId(groupId, target.sourcePath),
                        sourcePath = target.sourcePath,
                        baseSourceHash = staged.baseSourceHash,
                        targetSourceHash = staged.targetSourceHash,
                        stagedTargetPath = staged.stagedTargetPath,
                        state = MemoryMutationState.PREPARED,
                        idempotencyKeyBase = "memory-mutation-receipt:$semanticJobId:${target.sourcePath}",
                        targetIndexFingerprint = target.targetIndexFingerprint
                    )
                },
                createdAt = now()
            )
        )
        return MemoryPreparedMutation(prepared.group, prepared.receipts)
    }

    suspend fun prepareLocalMutation(
        operationKey: String,
        targets: List<MemoryMutationTarget>
    ): MemoryPreparedMutation {
        require(operationKey.isNotBlank()) { "Local mutation operation key must not be blank" }
        require(targets.singleOrNull()?.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
            "A local mutation must target only the long-term memory file"
        }
        require(targets.single().targetIndexFingerprint?.let(SHA_256_REGEX::matches) == true) {
            "A long-term local mutation requires a SHA-256 index fingerprint"
        }
        return localMutationPrepareMutex.withLock {
            prepareLocalMutationLocked(operationKey, targets)
        }
    }

    private suspend fun prepareLocalMutationLocked(
        operationKey: String,
        targets: List<MemoryMutationTarget>
    ): MemoryPreparedMutation {
        val observedCorpusGeneration = recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)?.generation ?: 0L
        val targetIdentity = targets.sortedBy(MemoryMutationTarget::sourcePath).joinToString(separator = "|") { target ->
            listOf(
                target.sourcePath,
                target.baseContent.toByteArray(Charsets.UTF_8).sha256Hex(),
                target.normalizedTargetSourceHash(),
                target.targetIndexFingerprint.orEmpty()
            ).joinToString(separator = ":")
        }
        val identity = "$operationKey|$targetIdentity|after=$observedCorpusGeneration"
        val groupId = "mutation_local_${identity.sha256Utf8().take(ID_HASH_LENGTH)}"
        recoveryDao.getMutationGroup(groupId)?.let { group ->
            val existing = loadMutation(group)
            val expectedTargets = targets.associateBy(MemoryMutationTarget::sourcePath)
            check(
                group.semanticJobId == null &&
                    group.semanticBatchId == null &&
                    existing.receipts.size == expectedTargets.size &&
                    existing.receipts.all { receipt ->
                        expectedTargets[receipt.sourcePath]?.let { target ->
                            receipt.baseSourceHash == target.baseContent.toByteArray(Charsets.UTF_8).sha256Hex() &&
                                receipt.targetSourceHash == target.normalizedTargetSourceHash() &&
                                receipt.targetIndexFingerprint == target.targetIndexFingerprint
                        } == true
                    }
            ) { "Existing local mutation does not match the requested identity" }
            return existing
        }

        memoryFileStore.cleanupStagedTargets(groupId).getOrThrow()
        val stagedTargets = targets.sortedBy(MemoryMutationTarget::sourcePath).map { target ->
            val receiptId = receiptId(groupId, target.sourcePath)
            val expectedBaseHash = target.baseContent.toByteArray(Charsets.UTF_8).sha256Hex()
            val staged = memoryFileStore.stageMemoryFile(
                sourcePath = target.sourcePath,
                content = target.targetContent,
                stagingId = receiptId
            ).getOrThrow()
            check(staged.baseSourceHash == expectedBaseHash) {
                "Memory source changed before its local mutation receipt was prepared"
            }
            target to staged
        }
        val idempotencyKeyBase = "memory-local-mutation:${identity.sha256Utf8()}"
        val prepared = recoveryDao.prepareMutation(
            MemoryMutationPrepareRequest(
                groupId = groupId,
                semanticJobId = null,
                semanticBatchId = null,
                state = MemoryMutationState.PREPARED,
                idempotencyKeyBase = idempotencyKeyBase,
                receipts = stagedTargets.map { (target, staged) ->
                    MemoryMutationReceiptDraft(
                        receiptId = receiptId(groupId, target.sourcePath),
                        sourcePath = target.sourcePath,
                        baseSourceHash = staged.baseSourceHash,
                        targetSourceHash = staged.targetSourceHash,
                        stagedTargetPath = staged.stagedTargetPath,
                        state = MemoryMutationState.PREPARED,
                        idempotencyKeyBase = "$idempotencyKeyBase:${target.sourcePath}",
                        targetIndexFingerprint = target.targetIndexFingerprint
                    )
                },
                createdAt = now()
            )
        )
        return MemoryPreparedMutation(prepared.group, prepared.receipts)
    }

    suspend fun prepareLocalIndexBootstrap(
        sourceContent: String,
        sourceHash: String,
        targetIndexFingerprint: String,
        observedCorpusGeneration: Long
    ): MemoryPreparedMutation {
        require(SHA_256_REGEX.matches(sourceHash)) { "Bootstrap source hash must be SHA-256" }
        require(SHA_256_REGEX.matches(targetIndexFingerprint)) { "Bootstrap index fingerprint must be SHA-256" }
        require(observedCorpusGeneration >= 0) { "Observed corpus generation must not be negative" }
        check(sourceContent.toByteArray(Charsets.UTF_8).sha256Hex() == sourceHash) {
            "Bootstrap content does not match its source hash"
        }

        val identity = "$sourceHash|$targetIndexFingerprint|after=$observedCorpusGeneration"
        val groupId = "mutation_bootstrap_${identity.sha256Utf8().take(ID_HASH_LENGTH)}"
        recoveryDao.getMutationGroup(groupId)?.let { group ->
            val existing = loadMutation(group)
            check(
                group.semanticJobId == null &&
                    group.semanticBatchId == null &&
                    existing.receipts.singleOrNull()?.let { receipt ->
                        receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME &&
                            receipt.baseSourceHash == sourceHash &&
                            receipt.targetSourceHash == sourceHash &&
                            receipt.targetIndexFingerprint == targetIndexFingerprint
                    } == true
            ) { "Existing local bootstrap mutation does not match the requested identity" }
            return existing
        }

        memoryFileStore.cleanupStagedTargets(groupId).getOrThrow()
        val receiptId = receiptId(groupId, MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME)
        val staged = memoryFileStore.stageMemoryFile(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            content = sourceContent,
            stagingId = receiptId
        ).getOrThrow()
        check(staged.baseSourceHash == sourceHash) {
            "Memory source changed before its bootstrap receipt was prepared"
        }
        if (staged.targetSourceHash != sourceHash) {
            memoryFileStore.cleanupStagedTarget(staged.stagedTargetPath).getOrThrow()
            error("Bootstrap receipt must not change canonical memory content")
        }
        val idempotencyKeyBase = "memory-vector-bootstrap:$identity"
        val prepared = recoveryDao.prepareMutation(
            MemoryMutationPrepareRequest(
                groupId = groupId,
                semanticJobId = null,
                semanticBatchId = null,
                state = MemoryMutationState.PREPARED,
                idempotencyKeyBase = idempotencyKeyBase,
                receipts = listOf(
                    MemoryMutationReceiptDraft(
                        receiptId = receiptId,
                        sourcePath = staged.sourcePath,
                        baseSourceHash = staged.baseSourceHash,
                        targetSourceHash = staged.targetSourceHash,
                        stagedTargetPath = staged.stagedTargetPath,
                        state = MemoryMutationState.PREPARED,
                        idempotencyKeyBase = "$idempotencyKeyBase:${staged.sourcePath}",
                        targetIndexFingerprint = targetIndexFingerprint
                    )
                ),
                createdAt = now()
            )
        )
        return MemoryPreparedMutation(prepared.group, prepared.receipts)
    }

    suspend fun reconcile(mutation: MemoryPreparedMutation): MemoryMutationCommitResult {
        var current = loadMutation(checkNotNull(recoveryDao.getMutationGroup(mutation.group.groupId)))
        if (current.group.state == MemoryMutationState.SUPERSEDED) {
            return MemoryMutationCommitResult.CanonicalCommitted(
                mutation = current,
                hasPendingIndex = false,
                requiresSemanticAcknowledgement = false
            )
        }
        current.receipts.firstOrNull { receipt -> receipt.state == MemoryMutationState.CONFLICT }?.let { conflict ->
            return markConflict(
                mutation = current,
                receipt = conflict,
                reason = conflict.lastError ?: current.group.lastError ?: "memory_mutation_conflict"
            )
        }
        if (
            current.group.state == MemoryMutationState.SEMANTIC_ACK_PENDING &&
            current.receipts.any { receipt -> receipt.state == MemoryMutationState.SUPERSEDED }
        ) {
            return MemoryMutationCommitResult.CanonicalCommitted(
                mutation = current,
                hasPendingIndex = false,
                requiresSemanticAcknowledgement = true
            )
        }
        if (isCommittedMutationSuperseded(current)) {
            current = markSuperseded(current)
            return MemoryMutationCommitResult.CanonicalCommitted(
                mutation = current,
                hasPendingIndex = false,
                requiresSemanticAcknowledgement = current.group.state == MemoryMutationState.SEMANTIC_ACK_PENDING
            )
        }

        current.receipts.sortedBy(MemoryMutationReceipt::sourcePath).forEach { originalReceipt ->
            var receipt = checkNotNull(recoveryDao.getMutationReceipt(originalReceipt.receiptId))
            if (receipt.state == MemoryMutationState.PREPARED) {
                if (isSupersededLongTermReceipt(receipt)) {
                    return markConflict(current, receipt, "superseded_mutation_generation")
                }
                when (
                    val outcome = memoryFileStore.commitStagedMemoryFile(
                        sourcePath = receipt.sourcePath,
                        stagedTargetPath = receipt.stagedTargetPath,
                        baseSourceHash = receipt.baseSourceHash,
                        targetSourceHash = receipt.targetSourceHash
                    ).getOrThrow()
                ) {
                    is MemoryFileCommitOutcome.Conflict ->
                        return markConflict(current, receipt, "canonical_source_hash_conflict")
                    is MemoryFileCommitOutcome.UnrecoverableStaging ->
                        return markConflict(current, receipt, outcome.reason)
                    is MemoryFileCommitOutcome.AlreadyCommitted,
                    is MemoryFileCommitOutcome.Committed -> {
                        receipt = transitionReceipt(
                            receipt = receipt,
                            newState = MemoryMutationState.FILE_COMMITTED,
                            fileCommittedAt = now()
                        )
                    }
                }
            } else if (receipt.state in COMMITTED_RECEIPT_STATES) {
                val currentHash = memoryFileStore.currentMemoryFileHash(receipt.sourcePath).getOrThrow()
                if (currentHash != receipt.targetSourceHash) {
                    return markConflict(current, receipt, "committed_source_hash_changed")
                }
            }

            if (receipt.state == MemoryMutationState.FILE_COMMITTED) {
                receipt = if (receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
                    transitionReceipt(receipt, MemoryMutationState.INDEX_PENDING)
                } else {
                    transitionReceipt(receipt, MemoryMutationState.INDEXED, indexedAt = now())
                        .also { indexedReceipt ->
                            memoryFileStore.cleanupStagedTarget(indexedReceipt.stagedTargetPath)
                        }
                }
            }
        }

        current = loadMutation(current.group)
        val longTermReceipt = current.receipts.singleOrNull { receipt ->
            receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        }
        if (longTermReceipt != null) {
            val fingerprint = checkNotNull(longTermReceipt.targetIndexFingerprint) {
                "Long-term mutation receipt is missing its target index fingerprint"
            }
            val corpusAdvance = recoveryDao.advanceCorpusGeneration(
                MemoryCorpusAdvanceRequest(
                    corpus = CHAT_RECALL_CORPUS_KEY,
                    sourcePath = longTermReceipt.sourcePath,
                    sourceHash = longTermReceipt.targetSourceHash,
                    generation = current.group.generation,
                    targetIndexFingerprint = fingerprint,
                    indexStatus = MemoryCorpusIndexStatus.PENDING,
                    latestReceiptId = longTermReceipt.receiptId,
                    createdAt = current.group.createdAt,
                    updatedAt = now()
                )
            )
            if (corpusAdvance.outcome == MemoryCorpusAdvanceOutcome.STALE) {
                return markConflict(current, longTermReceipt, "superseded_corpus_generation")
            }
        }

        current = completeCanonicalGroup(current)
        if (longTermReceipt != null) {
            scheduleIndexSync(current.group, longTermReceipt)
        }
        return MemoryMutationCommitResult.CanonicalCommitted(
            mutation = current,
            hasPendingIndex = longTermReceipt != null,
            requiresSemanticAcknowledgement = current.group.state == MemoryMutationState.SEMANTIC_ACK_PENDING
        )
    }

    suspend fun acknowledgeSemanticCompletion(groupId: String): MemoryPreparedMutation {
        val current = loadMutation(checkNotNull(recoveryDao.getMutationGroup(groupId)))
        if (current.group.state != MemoryMutationState.SEMANTIC_ACK_PENDING) return current
        val newState = when {
            current.receipts.any { receipt -> receipt.state == MemoryMutationState.SUPERSEDED } ->
                MemoryMutationState.SUPERSEDED
            current.receipts.any { receipt -> receipt.state == MemoryMutationState.INDEX_PENDING } ->
                MemoryMutationState.INDEX_PENDING
            else -> MemoryMutationState.INDEXED
        }
        val changed = recoveryDao.transitionMutationGroupCas(
            groupId = current.group.groupId,
            expectedGeneration = current.group.generation,
            expectedState = current.group.state,
            expectedRowVersion = current.group.rowVersion,
            newState = newState,
            lastError = null,
            updatedAt = now(),
            completedAt = current.group.completedAt ?: now()
        )
        if (changed != 1) {
            val reloaded = loadMutation(checkNotNull(recoveryDao.getMutationGroup(groupId)))
            check(reloaded.group.state == newState) {
                "Mutation group changed before semantic completion was acknowledged"
            }
            return reloaded
        }
        return loadMutation(checkNotNull(recoveryDao.getMutationGroup(groupId)))
    }

    suspend fun reconcileIncomplete(limit: Int = DEFAULT_REPAIR_LIMIT): MemoryMutationRepairResult {
        require(limit > 0) { "Mutation repair limit must be positive" }
        var committedCount = 0
        var conflictCount = 0
        var failedCount = 0
        val recoveredSemanticMutations = linkedSetOf<MemoryRecoveredSemanticMutation>()
        val failedGenerations = linkedSetOf<Long>()
        val incompleteGroups = recoveryDao.getMutationGroupsByStates(MemoryMutationState.INCOMPLETE)
        val orderedGroups = incompleteGroups.filter { group -> group.state != MemoryMutationState.INDEX_PENDING } +
            incompleteGroups.filter { group -> group.state == MemoryMutationState.INDEX_PENDING }
        val groupsToRepair = orderedGroups.take(limit)
        groupsToRepair
            .forEach { group ->
                try {
                    when (val result = reconcile(loadMutation(group))) {
                        is MemoryMutationCommitResult.CanonicalCommitted -> {
                            committedCount += 1
                            if (result.requiresSemanticAcknowledgement) {
                                result.mutation.group.semanticJobId?.let { semanticJobId ->
                                    recoveredSemanticMutations += MemoryRecoveredSemanticMutation(
                                        groupId = result.mutation.group.groupId,
                                        semanticJobId = semanticJobId,
                                        generation = result.mutation.group.generation
                                    )
                                }
                            }
                        }
                        is MemoryMutationCommitResult.Conflict -> {
                            conflictCount += 1
                            if (result.requiresSemanticFinalization) {
                                result.mutation.group.semanticJobId?.let { semanticJobId ->
                                    recoveredSemanticMutations += MemoryRecoveredSemanticMutation(
                                        groupId = result.mutation.group.groupId,
                                        semanticJobId = semanticJobId,
                                        generation = result.mutation.group.generation,
                                        terminalReason = result.reason
                                    )
                                }
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    failedCount += 1
                    failedGenerations += group.generation
                }
            }
        return MemoryMutationRepairResult(
            committedCount = committedCount,
            conflictCount = conflictCount,
            failedCount = failedCount,
            recoveredSemanticMutations = recoveredSemanticMutations,
            failedGenerations = failedGenerations,
            hasMore = orderedGroups.size > groupsToRepair.size,
            continuationGeneration = orderedGroups.getOrNull(groupsToRepair.size)?.generation
        )
    }

    internal suspend fun terminalSemanticConflicts(): List<MemoryRecoveredSemanticMutation> =
        recoveryDao.getMutationGroupsByStates(listOf(MemoryMutationState.CONFLICT))
            .mapNotNull { group ->
                val semanticJobId = group.semanticJobId ?: return@mapNotNull null
                val terminalReason = group.lastError
                    ?.takeIf(String::isNotBlank)
                    ?: recoveryDao.getMutationReceipts(group.groupId)
                        .firstNotNullOfOrNull { receipt ->
                            receipt.lastError?.takeIf(String::isNotBlank)
                        }
                    ?: return@mapNotNull null
                MemoryRecoveredSemanticMutation(
                    groupId = group.groupId,
                    semanticJobId = semanticJobId,
                    generation = group.generation,
                    terminalReason = terminalReason
                )
            }

    private suspend fun completeCanonicalGroup(mutation: MemoryPreparedMutation): MemoryPreparedMutation {
        val group = mutation.group
        if (
            group.state in setOf(
                MemoryMutationState.SEMANTIC_ACK_PENDING,
                MemoryMutationState.INDEX_PENDING,
                MemoryMutationState.INDEXED,
                MemoryMutationState.SUPERSEDED
            )
        ) {
            return mutation
        }
        val newState = when {
            group.semanticJobId != null -> MemoryMutationState.SEMANTIC_ACK_PENDING
            mutation.receipts.any { receipt -> receipt.state == MemoryMutationState.INDEX_PENDING } ->
                MemoryMutationState.INDEX_PENDING
            else -> MemoryMutationState.INDEXED
        }
        val changed = if (mutation.receipts.isEmpty()) {
            recoveryDao.completeEmptyMutationGroupCas(
                groupId = group.groupId,
                expectedGeneration = group.generation,
                expectedState = group.state,
                expectedRowVersion = group.rowVersion,
                newState = newState,
                completedAt = now()
            )
        } else {
            recoveryDao.completeMutationGroupIfReceiptsCommitted(
                groupId = group.groupId,
                expectedGeneration = group.generation,
                expectedState = group.state,
                expectedRowVersion = group.rowVersion,
                committedReceiptStates = COMMITTED_RECEIPT_STATES,
                newState = newState,
                completedAt = now()
            )
        }
        if (changed != 1) {
            val reloaded = loadMutation(checkNotNull(recoveryDao.getMutationGroup(group.groupId)))
            check(reloaded.group.state == newState) { "Mutation group changed before canonical completion" }
            return reloaded
        }
        return loadMutation(checkNotNull(recoveryDao.getMutationGroup(group.groupId)))
    }

    private suspend fun transitionReceipt(
        receipt: MemoryMutationReceipt,
        newState: String,
        fileCommittedAt: Long? = null,
        indexedAt: Long? = null,
        lastError: String? = null
    ): MemoryMutationReceipt {
        val changed = recoveryDao.transitionMutationReceiptCas(
            receiptId = receipt.receiptId,
            groupId = receipt.groupId,
            expectedGeneration = receipt.generation,
            expectedState = receipt.state,
            expectedRowVersion = receipt.rowVersion,
            expectedTargetSourceHash = receipt.targetSourceHash,
            expectedTargetIndexFingerprint = receipt.targetIndexFingerprint,
            newState = newState,
            attemptIncrement = 0,
            lastError = lastError,
            updatedAt = now(),
            fileCommittedAt = fileCommittedAt,
            indexedAt = indexedAt
        )
        if (changed != 1) {
            val current = checkNotNull(recoveryDao.getMutationReceipt(receipt.receiptId))
            check(current.state == newState) { "Mutation receipt changed before its state transition" }
            return current
        }
        return checkNotNull(recoveryDao.getMutationReceipt(receipt.receiptId))
    }

    private suspend fun markConflict(
        mutation: MemoryPreparedMutation,
        receipt: MemoryMutationReceipt,
        reason: String
    ): MemoryMutationCommitResult.Conflict {
        val group = checkNotNull(recoveryDao.getMutationGroup(mutation.group.groupId))
        val requiresSemanticFinalization = group.state in SEMANTIC_FINALIZATION_PENDING_STATES
        val conflictReason = receipt.lastError.takeIf { receipt.state == MemoryMutationState.CONFLICT } ?: reason
        val completedAt = now()

        if (receipt.state != MemoryMutationState.CONFLICT && group.state != MemoryMutationState.CONFLICT) {
            val changed = recoveryDao.transitionMutationToConflictCas(
                MemoryMutationConflictRequest(
                    receiptId = receipt.receiptId,
                    groupId = group.groupId,
                    generation = receipt.generation,
                    expectedReceiptState = receipt.state,
                    expectedReceiptRowVersion = receipt.rowVersion,
                    expectedTargetSourceHash = receipt.targetSourceHash,
                    expectedTargetIndexFingerprint = receipt.targetIndexFingerprint,
                    expectedGroupState = group.state,
                    expectedGroupRowVersion = group.rowVersion,
                    reason = conflictReason,
                    completedAt = completedAt
                )
            )
            if (changed != 1) {
                val currentReceipt = checkNotNull(recoveryDao.getMutationReceipt(receipt.receiptId))
                val currentGroup = checkNotNull(recoveryDao.getMutationGroup(group.groupId))
                check(
                    currentReceipt.state == MemoryMutationState.CONFLICT &&
                        currentGroup.state == MemoryMutationState.CONFLICT
                ) { "Mutation changed before it could be marked conflicted" }
            }
        } else {
            if (receipt.state != MemoryMutationState.CONFLICT) {
                transitionReceipt(receipt, MemoryMutationState.CONFLICT, lastError = conflictReason)
            }
            if (group.state != MemoryMutationState.CONFLICT) {
                val changed = recoveryDao.transitionMutationGroupCas(
                    groupId = group.groupId,
                    expectedGeneration = group.generation,
                    expectedState = group.state,
                    expectedRowVersion = group.rowVersion,
                    newState = MemoryMutationState.CONFLICT,
                    lastError = conflictReason,
                    updatedAt = completedAt,
                    completedAt = completedAt
                )
                if (changed != 1) {
                    check(recoveryDao.getMutationGroup(group.groupId)?.state == MemoryMutationState.CONFLICT) {
                        "Mutation group changed before it could be marked conflicted"
                    }
                }
            }
        }
        val current = loadMutation(checkNotNull(recoveryDao.getMutationGroup(group.groupId)))
        val conflictedReceipt = checkNotNull(
            current.receipts.firstOrNull { currentReceipt -> currentReceipt.receiptId == receipt.receiptId }
        )
        return MemoryMutationCommitResult.Conflict(
            mutation = current,
            sourcePath = conflictedReceipt.sourcePath,
            reason = current.group.lastError ?: conflictedReceipt.lastError ?: conflictReason,
            requiresSemanticFinalization = requiresSemanticFinalization && current.group.semanticJobId != null
        )
    }

    private suspend fun isSupersededLongTermReceipt(receipt: MemoryMutationReceipt): Boolean {
        if (receipt.sourcePath != MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) return false
        return recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)?.generation?.let { generation ->
            generation > receipt.generation
        } ?: false
    }

    private suspend fun isCommittedMutationSuperseded(mutation: MemoryPreparedMutation): Boolean {
        val longTermReceipt = mutation.receipts.singleOrNull { receipt ->
            receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        } ?: return false
        if (mutation.receipts.any { receipt -> receipt.state !in COMMITTED_RECEIPT_STATES }) return false
        return isSupersededLongTermReceipt(longTermReceipt)
    }

    private suspend fun markSuperseded(mutation: MemoryPreparedMutation): MemoryPreparedMutation {
        mutation.receipts
            .filter { receipt ->
                receipt.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME &&
                    receipt.state != MemoryMutationState.SUPERSEDED
            }
            .forEach { receipt ->
                transitionReceipt(receipt, MemoryMutationState.SUPERSEDED)
                memoryFileStore.cleanupStagedTarget(receipt.stagedTargetPath)
            }
        val group = checkNotNull(recoveryDao.getMutationGroup(mutation.group.groupId))
        val targetGroupState = if (group.state == MemoryMutationState.SEMANTIC_ACK_PENDING) {
            MemoryMutationState.SEMANTIC_ACK_PENDING
        } else {
            MemoryMutationState.SUPERSEDED
        }
        if (group.state != targetGroupState) {
            val changed = recoveryDao.transitionMutationGroupCas(
                groupId = group.groupId,
                expectedGeneration = group.generation,
                expectedState = group.state,
                expectedRowVersion = group.rowVersion,
                newState = targetGroupState,
                lastError = "superseded_by_newer_corpus_generation",
                updatedAt = now(),
                completedAt = now()
            )
            if (changed != 1) {
                check(recoveryDao.getMutationGroup(group.groupId)?.state == targetGroupState) {
                    "Mutation group changed before it could be marked superseded"
                }
            }
        }
        return loadMutation(checkNotNull(recoveryDao.getMutationGroup(group.groupId)))
    }

    private suspend fun scheduleIndexSync(group: MemoryMutationGroup, receipt: MemoryMutationReceipt) {
        try {
            maintenanceScheduler.enqueue(
                type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
                idempotencyKey = "memory-vector-sync",
                payloadJson = json.encodeToString(
                    MemoryIndexSyncJobPayload(
                        mutationGroupId = group.groupId,
                        receiptId = receipt.receiptId,
                        generation = group.generation,
                        sourcePath = receipt.sourcePath,
                        sourceHash = receipt.targetSourceHash,
                        targetIndexFingerprint = checkNotNull(receipt.targetIndexFingerprint)
                    )
                ),
                generation = group.generation
            )
            workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.INDEX)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            Unit
        }
    }

    private suspend fun loadMutation(group: MemoryMutationGroup): MemoryPreparedMutation =
        MemoryPreparedMutation(group, recoveryDao.getMutationReceipts(group.groupId))

    private fun receiptId(groupId: String, sourcePath: String): String =
        "${groupId}_receipt_${sourcePath.sha256Utf8().take(RECEIPT_PATH_HASH_LENGTH)}"

    private fun MemoryMutationTarget.normalizedTargetSourceHash(): String =
        (targetContent.trimEnd() + "\n").toByteArray(Charsets.UTF_8).sha256Hex()

    private fun now(): Long = clock.instant().epochSecond

    private companion object {
        const val ID_HASH_LENGTH = 24
        const val RECEIPT_PATH_HASH_LENGTH = 16
        const val DEFAULT_REPAIR_LIMIT = 100
        val COMMITTED_RECEIPT_STATES = listOf(
            MemoryMutationState.FILE_COMMITTED,
            MemoryMutationState.INDEX_PENDING,
            MemoryMutationState.INDEXED
        )
        val SEMANTIC_FINALIZATION_PENDING_STATES = setOf(
            MemoryMutationState.PREPARED,
            MemoryMutationState.FILE_COMMITTED,
            MemoryMutationState.SEMANTIC_ACK_PENDING,
            MemoryMutationState.FAILED
        )
        val CHAT_RECALL_CORPUS_KEY = MemoryCorpus.CHAT_RECALL_LONG_TERM.name.lowercase()
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}

data class MemoryMutationRepairResult(
    val committedCount: Int,
    val conflictCount: Int,
    val failedCount: Int,
    val recoveredSemanticMutations: Set<MemoryRecoveredSemanticMutation> = emptySet(),
    val failedGenerations: Set<Long> = emptySet(),
    val hasMore: Boolean = false,
    val continuationGeneration: Long? = null
)
