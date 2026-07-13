package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryVectorIndexPublicationOutcome
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryVectorIndexPublicationRequest
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryCorpusState
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingAvailability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshotExpectation
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshotVerification
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStore
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryVectorIndexRecoveryService(
    private val recoveryDao: MemoryRecoveryDao,
    private val snapshotSource: MemoryCorpusSnapshotSource,
    private val memoryFileStore: MemoryFileStore,
    private val vectorStore: MemoryVectorStore,
    private val embeddingCapabilitySource: MemoryEmbeddingCapabilitySource,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json
) {
    suspend fun reconcile(): MemoryVectorIndexRecoveryResult {
        var completedCount = 0
        var scheduledCount = 0
        var blockedCount = 0
        var conflictCount = 0
        var deletedDerivedStoreCount = 0
        val states = recoveryDao.getCorpusStatesByIndexStatuses(RECONCILABLE_INDEX_STATUSES)
        for (state in states) {
            if (state.corpus != CHAT_RECALL_CORPUS_KEY) continue
            try {
                val result = reconcile(state)
                completedCount += result.completedCount
                scheduledCount += result.scheduledCount
                blockedCount += result.blockedCount
                conflictCount += result.conflictCount
                deletedDerivedStoreCount += result.deletedDerivedStoreCount
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: MemoryCorpusRecoverySupersededException) {
                Unit
            }
        }
        return MemoryVectorIndexRecoveryResult(
            completedCount = completedCount,
            scheduledCount = scheduledCount,
            blockedCount = blockedCount,
            conflictCount = conflictCount,
            deletedDerivedStoreCount = deletedDerivedStoreCount
        )
    }

    private suspend fun reconcile(state: MemoryCorpusState): MemoryVectorIndexRecoveryResult {
        val payload = payloadFor(state) ?: run {
            val reason = "room_corpus_is_missing_vector_receipt_identity"
            transitionCorpusStatus(
                state = state,
                status = MemoryCorpusIndexStatus.WAITING_REPAIR,
                reason = reason
            )
            maintenanceScheduler.markGenerationAwareTerminal(
                type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
                idempotencyKey = SYNC_IDEMPOTENCY_KEY,
                generation = state.generation,
                reason = reason
            )
            return MemoryVectorIndexRecoveryResult(conflictCount = 1)
        }
        val receipt = recoveryDao.getMutationReceipt(payload.receiptId) ?: run {
            transitionCorpusStatus(
                state = state,
                status = MemoryCorpusIndexStatus.WAITING_REPAIR,
                reason = "vector_index_receipt_missing"
            )
            return MemoryVectorIndexRecoveryResult(conflictCount = 1)
        }
        if (
            receipt.groupId != payload.mutationGroupId ||
            receipt.generation != payload.generation ||
            receipt.sourcePath != payload.sourcePath ||
            receipt.targetSourceHash != payload.sourceHash ||
            receipt.targetIndexFingerprint != payload.targetIndexFingerprint
        ) {
            return markConflict(state, payload, "vector_index_receipt_identity_conflict")
        }
        val group = recoveryDao.getMutationGroup(payload.mutationGroupId)
        if (
            group == null ||
            group.generation != payload.generation ||
            receipt.state !in setOf(MemoryMutationState.INDEX_PENDING, MemoryMutationState.INDEXED) ||
            group.state !in setOf(
                MemoryMutationState.SEMANTIC_ACK_PENDING,
                MemoryMutationState.INDEX_PENDING,
                MemoryMutationState.INDEXED
            )
        ) {
            return markConflict(state, payload, "vector_index_mutation_state_conflict")
        }
        val snapshot = snapshotSource.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow().singleOrNull()
        if (
            snapshot == null ||
            snapshot.sourcePath != state.sourcePath ||
            snapshot.sourceHash != state.sourceHash
        ) {
            return markConflict(state, payload, "canonical_memory_does_not_match_room_corpus")
        }

        return when (val verification = vectorStore.verifySnapshot(state.toExpectation(snapshot.chunks))) {
            is MemoryVectorSnapshotVerification.Ready -> {
                if (!snapshotSource.isCurrent(listOf(snapshot)).getOrThrow()) {
                    return markConflict(state, payload, "canonical_memory_changed_before_room_fast_forward")
                }
                when (
                    recoveryDao.completeVectorIndexPublication(
                    MemoryVectorIndexPublicationRequest(
                        corpus = state.corpus,
                        mutationGroupId = payload.mutationGroupId,
                        receiptId = payload.receiptId,
                        generation = payload.generation,
                        sourcePath = payload.sourcePath,
                        sourceHash = payload.sourceHash,
                        targetIndexFingerprint = payload.targetIndexFingerprint,
                        completedAt = now()
                    )
                    )
                ) {
                    MemoryVectorIndexPublicationOutcome.COMPLETED,
                    MemoryVectorIndexPublicationOutcome.ALREADY_COMPLETE -> {
                        memoryFileStore.cleanupStagedTarget(receipt.stagedTargetPath).getOrThrow()
                        val job = ensureSyncJob(payload)
                        maintenanceScheduler.markRecoveredSucceeded(job.jobId)
                        MemoryVectorIndexRecoveryResult(completedCount = 1)
                    }
                    MemoryVectorIndexPublicationOutcome.SUPERSEDED ->
                        MemoryVectorIndexRecoveryResult()
                    MemoryVectorIndexPublicationOutcome.CONFLICT ->
                        markConflict(state, payload, "room_vector_publication_conflict")
                }
            }
            is MemoryVectorSnapshotVerification.Stale -> {
                val manifestGeneration = verification.manifest.identity.corpusGeneration
                if (manifestGeneration < state.generation) {
                    recoverMissingSnapshot(state, payload, deletedDerivedStoreCount = 0)
                } else {
                    val latest = recoveryDao.getCorpusState(state.corpus)
                    if (latest != null && latest.generation > state.generation) {
                        MemoryVectorIndexRecoveryResult()
                    } else {
                        markConflict(
                            state = state,
                            payload = payload,
                            reason = if (manifestGeneration == state.generation) {
                                "same_generation_vector_manifest_conflict"
                            } else {
                                "vector_manifest_is_newer_than_room_corpus"
                            }
                        )
                    }
                }
            }
            MemoryVectorSnapshotVerification.Missing ->
                recoverMissingSnapshot(state, payload, deletedDerivedStoreCount = 0)
            MemoryVectorSnapshotVerification.RecoveredCorruption ->
                recoverMissingSnapshot(state, payload, deletedDerivedStoreCount = 1)
        }
    }

    private suspend fun markConflict(
        state: MemoryCorpusState,
        payload: MemoryIndexSyncJobPayload,
        reason: String
    ): MemoryVectorIndexRecoveryResult {
        transitionCorpusStatus(
            state = state,
            status = MemoryCorpusIndexStatus.WAITING_REPAIR,
            reason = reason
        )
        val job = ensureSyncJob(payload)
        maintenanceScheduler.markRecoveredConflict(job.jobId, reason)
        return MemoryVectorIndexRecoveryResult(conflictCount = 1)
    }

    private suspend fun recoverMissingSnapshot(
        state: MemoryCorpusState,
        payload: MemoryIndexSyncJobPayload,
        deletedDerivedStoreCount: Int
    ): MemoryVectorIndexRecoveryResult {
        val availability = embeddingAvailability()
        val job = ensureSyncJob(payload)
        return when (availability) {
            MemoryEmbeddingAvailability.Available,
            MemoryEmbeddingAvailability.Loading -> {
                transitionCorpusStatus(
                    state = state,
                    status = MemoryCorpusIndexStatus.PENDING,
                    reason = null
                )
                maintenanceScheduler.reviveLocalJob(job.jobId)
                MemoryVectorIndexRecoveryResult(
                    scheduledCount = 1,
                    deletedDerivedStoreCount = deletedDerivedStoreCount
                )
            }
            is MemoryEmbeddingAvailability.Unavailable -> {
                val reason = "embedding_unavailable:${availability.reason.name.lowercase()}"
                transitionCorpusStatus(
                    state = state,
                    status = MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY,
                    reason = reason
                )
                if (job.status != MemoryMaintenanceJobStatus.RUNNING && job.status != MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY) {
                    maintenanceScheduler.markBlockedDependency(job, reason)
                }
                MemoryVectorIndexRecoveryResult(
                    blockedCount = 1,
                    deletedDerivedStoreCount = deletedDerivedStoreCount
                )
            }
        }
    }

    private suspend fun embeddingAvailability(): MemoryEmbeddingAvailability = when (
        val capability = embeddingCapabilitySource.current()
    ) {
        is MemoryEmbeddingCapability.Ready -> capability.provider.availability()
        is MemoryEmbeddingCapability.Unavailable -> capability.availability
    }

    private suspend fun ensureSyncJob(payload: MemoryIndexSyncJobPayload) = maintenanceScheduler.enqueue(
        type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
        idempotencyKey = SYNC_IDEMPOTENCY_KEY,
        payloadJson = json.encodeToString(payload),
        generation = payload.generation
    )

    private suspend fun transitionCorpusStatus(
        state: MemoryCorpusState,
        status: String,
        reason: String?
    ) {
        val latest = recoveryDao.getCorpusState(state.corpus)
            ?: throw MemoryCorpusRecoverySupersededException()
        if (!latest.matchesIdentity(state)) throw MemoryCorpusRecoverySupersededException()
        if (latest.indexStatus == status && latest.lastError == reason) return
        check(
            recoveryDao.transitionCorpusIndexStatusCas(
                corpus = latest.corpus,
                expectedGeneration = latest.generation,
                expectedSourceHash = latest.sourceHash,
                expectedTargetIndexFingerprint = latest.targetIndexFingerprint,
                expectedIndexStatus = latest.indexStatus,
                expectedRowVersion = latest.rowVersion,
                newIndexStatus = status,
                lastError = reason,
                updatedAt = now()
            ) == 1
        ) { "Memory corpus changed during vector startup recovery" }
    }

    private fun MemoryCorpusState.matchesIdentity(other: MemoryCorpusState): Boolean =
        corpus == other.corpus &&
            generation == other.generation &&
            sourcePath == other.sourcePath &&
            sourceHash == other.sourceHash &&
            targetIndexFingerprint == other.targetIndexFingerprint &&
            latestReceiptId == other.latestReceiptId

    private fun MemoryCorpusState.toExpectation(
        chunks: List<MemoryCorpusChunk>
    ): MemoryVectorSnapshotExpectation =
        MemoryVectorSnapshotExpectation(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = sourcePath,
            sourceHash = sourceHash,
            corpusGeneration = generation,
            indexFingerprint = checkNotNull(targetIndexFingerprint),
            chunks = chunks
        )

    private suspend fun payloadFor(state: MemoryCorpusState): MemoryIndexSyncJobPayload? {
        val receiptId = state.latestReceiptId?.takeIf(String::isNotBlank) ?: return null
        val fingerprint = state.targetIndexFingerprint?.takeIf { it.matches(SHA_256_REGEX) } ?: return null
        if (
            state.generation <= 0 ||
            state.sourcePath != MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME ||
            !state.sourceHash.matches(SHA_256_REGEX)
        ) {
            return null
        }
        val receipt = recoveryDao.getMutationReceipt(receiptId) ?: return null
        return MemoryIndexSyncJobPayload(
            mutationGroupId = receipt.groupId,
            receiptId = receiptId,
            generation = state.generation,
            sourcePath = state.sourcePath,
            sourceHash = state.sourceHash,
            targetIndexFingerprint = fingerprint
        )
    }

    private fun now(): Long = clock.instant().epochSecond

    private companion object {
        const val SYNC_IDEMPOTENCY_KEY = "memory-vector-sync"
        val CHAT_RECALL_CORPUS_KEY = MemoryCorpus.CHAT_RECALL_LONG_TERM.name.lowercase()
        val RECONCILABLE_INDEX_STATUSES = listOf(
            MemoryCorpusIndexStatus.PENDING,
            MemoryCorpusIndexStatus.READY,
            MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY,
            MemoryCorpusIndexStatus.WAITING_REPAIR
        )
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}

private class MemoryCorpusRecoverySupersededException : IllegalStateException()

data class MemoryVectorIndexRecoveryResult(
    val completedCount: Int = 0,
    val scheduledCount: Int = 0,
    val blockedCount: Int = 0,
    val conflictCount: Int = 0,
    val deletedDerivedStoreCount: Int = 0
)
