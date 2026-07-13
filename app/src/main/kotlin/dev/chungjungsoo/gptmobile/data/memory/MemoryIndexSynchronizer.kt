package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryVectorIndexPublicationOutcome
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryVectorIndexPublicationRequest
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryCorpusState
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationReceipt
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingAvailability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryEmbeddedChunk
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexConfiguration
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorManifest
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorManifestState
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorPublishResult
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshot
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshotExpectation
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshotVerification
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStore
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStoreConflictException
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStoreCorruptionException
import java.time.Clock
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MemoryIndexSynchronizer(
    private val recoveryDao: MemoryRecoveryDao,
    private val snapshotSource: MemoryCorpusSnapshotSource,
    private val memoryFileStore: MemoryFileStore,
    private val vectorStore: MemoryVectorStore,
    private val embeddingCapabilitySource: MemoryEmbeddingCapabilitySource,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json
) : MemoryIndexSyncService {
    override suspend fun synchronize(job: MemoryMaintenanceJob): MemoryIndexSyncResult {
        val payload = decodePayload(job) ?: return MemoryIndexSyncResult.Terminal("invalid_vector_index_payload")
        return try {
            synchronize(payload)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: MemoryVectorStoreConflictException) {
            MemoryIndexSyncResult.Terminal("vector_store_generation_conflict")
        } catch (throwable: Throwable) {
            if (recoverDerivedStore(throwable)) {
                MemoryIndexSyncResult.Retryable("vector_store_recovered_from_corruption")
            } else {
                MemoryIndexSyncResult.Retryable(
                    "vector_index_sync_failed:${throwable.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun synchronize(payload: MemoryIndexSyncJobPayload): MemoryIndexSyncResult {
        val initialState = when (val state = loadCurrentState(payload)) {
            is CurrentSyncState.Current -> state
            is CurrentSyncState.Superseded -> return MemoryIndexSyncResult.Superseded
            is CurrentSyncState.Retryable -> return MemoryIndexSyncResult.Retryable(state.reason)
            is CurrentSyncState.Conflict -> return MemoryIndexSyncResult.Terminal(state.reason)
        }
        val snapshot = currentSnapshot(payload)
            ?: return MemoryIndexSyncResult.Retryable("canonical_memory_does_not_match_vector_job")

        when (val verification = vectorStore.verifySnapshot(payload.toExpectation(snapshot.chunks))) {
            is MemoryVectorSnapshotVerification.Ready -> {
                if (!snapshotSource.isCurrent(listOf(snapshot)).getOrThrow()) {
                    return latestGenerationResult(payload, "canonical_memory_changed_before_room_fast_forward")
                }
                return completeRoomPublication(payload, initialState.receipt)
            }
            is MemoryVectorSnapshotVerification.Stale -> when (manifestStatus(verification.manifest, payload)) {
                ManifestStatus.SUPERSEDED -> return MemoryIndexSyncResult.Superseded
                ManifestStatus.CONFLICT ->
                    return MemoryIndexSyncResult.Terminal("same_generation_vector_manifest_conflict")
                ManifestStatus.REBUILD -> Unit
            }
            MemoryVectorSnapshotVerification.Missing,
            MemoryVectorSnapshotVerification.RecoveredCorruption -> Unit
        }

        val readyCapability = when (val capability = embeddingCapabilitySource.current()) {
            is MemoryEmbeddingCapability.Ready -> capability
            is MemoryEmbeddingCapability.Unavailable ->
                return availabilityResult(payload, capability.availability)
        }
        when (val availability = readyCapability.provider.availability()) {
            MemoryEmbeddingAvailability.Available -> Unit
            else -> return availabilityResult(payload, availability)
        }
        val configuration = readyCapability.configuration
        if (configuration.corpus != MemoryCorpus.CHAT_RECALL_LONG_TERM) {
            return MemoryIndexSyncResult.Terminal("embedding_configuration_has_wrong_corpus")
        }
        if (configuration.fingerprint() != payload.targetIndexFingerprint) {
            return markBlockedDependency(payload, "embedding_configuration_fingerprint_mismatch")
        }

        val embeddings = embedDocuments(snapshot, readyCapability, configuration)
            ?: return markBlockedDependency(payload, "embedding_provider_returned_invalid_vectors")
        if (!snapshotSource.isCurrent(listOf(snapshot)).getOrThrow()) {
            return latestGenerationResult(payload, "canonical_memory_changed_during_embedding")
        }
        when (val state = loadCurrentState(payload)) {
            is CurrentSyncState.Current -> Unit
            is CurrentSyncState.Superseded -> return MemoryIndexSyncResult.Superseded
            is CurrentSyncState.Retryable -> return MemoryIndexSyncResult.Retryable(state.reason)
            is CurrentSyncState.Conflict -> return MemoryIndexSyncResult.Terminal(state.reason)
        }

        val identity = configuration.identity(
            sourcePath = payload.sourcePath,
            sourceHash = payload.sourceHash,
            corpusGeneration = payload.generation
        )
        val vectorSnapshot = MemoryVectorSnapshot(
            manifest = MemoryVectorManifest(
                identity = identity,
                expectedChunkCount = snapshot.chunks.size.toLong(),
                completedAt = clock.instant().epochSecond,
                state = MemoryVectorManifestState.READY
            ),
            chunks = snapshot.chunks.zip(embeddings) { chunk, embedding ->
                MemoryEmbeddedChunk(chunk, embedding)
            }
        )
        when (vectorStore.replaceSnapshot(vectorSnapshot)) {
            MemoryVectorPublishResult.SUPERSEDED -> return MemoryIndexSyncResult.Superseded
            MemoryVectorPublishResult.PUBLISHED,
            MemoryVectorPublishResult.ALREADY_READY -> Unit
        }
        when (val verification = vectorStore.verifySnapshot(payload.toExpectation(snapshot.chunks))) {
            is MemoryVectorSnapshotVerification.Ready -> {
                if (verification.manifest.expectedChunkCount != snapshot.chunks.size.toLong()) {
                    throw MemoryVectorStoreCorruptionException("Published vector snapshot failed verification")
                }
            }
            is MemoryVectorSnapshotVerification.Stale,
            MemoryVectorSnapshotVerification.Missing,
            MemoryVectorSnapshotVerification.RecoveredCorruption ->
                throw MemoryVectorStoreCorruptionException("Published vector snapshot failed verification")
        }
        return completeRoomPublication(payload, initialState.receipt)
    }

    private fun decodePayload(job: MemoryMaintenanceJob): MemoryIndexSyncJobPayload? = try {
        check(job.family == MemoryMaintenanceJobFamily.INDEX)
        check(job.type == MemoryMaintenanceJobType.SYNC_VECTOR_INDEX)
        val payload = json.decodeFromString<MemoryIndexSyncJobPayload>(job.payloadJson)
        check(job.generation == payload.generation)
        check(payload.mutationGroupId.isNotBlank())
        check(payload.receiptId.isNotBlank())
        check(payload.generation > 0)
        check(payload.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME)
        check(payload.sourceHash.matches(SHA_256_REGEX))
        check(payload.targetIndexFingerprint.matches(SHA_256_REGEX))
        payload
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private suspend fun loadCurrentState(payload: MemoryIndexSyncJobPayload): CurrentSyncState {
        val corpus = recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)
            ?: return CurrentSyncState.Retryable("memory_corpus_state_missing")
        if (corpus.generation > payload.generation) return CurrentSyncState.Superseded
        if (corpus.generation < payload.generation) {
            return CurrentSyncState.Retryable("memory_corpus_generation_not_committed")
        }
        if (!corpus.matches(payload)) {
            return CurrentSyncState.Conflict("same_generation_room_corpus_conflict")
        }

        val receipt = recoveryDao.getMutationReceipt(payload.receiptId)
            ?: return CurrentSyncState.Conflict("vector_index_receipt_missing")
        val group = recoveryDao.getMutationGroup(payload.mutationGroupId)
            ?: return CurrentSyncState.Conflict("vector_index_mutation_group_missing")
        if (
            receipt.groupId != payload.mutationGroupId ||
            receipt.generation != payload.generation ||
            receipt.sourcePath != payload.sourcePath ||
            receipt.targetSourceHash != payload.sourceHash ||
            receipt.targetIndexFingerprint != payload.targetIndexFingerprint ||
            group.generation != payload.generation
        ) {
            return CurrentSyncState.Conflict("vector_index_receipt_identity_conflict")
        }
        if (receipt.state == MemoryMutationState.SUPERSEDED || group.state == MemoryMutationState.SUPERSEDED) {
            return CurrentSyncState.Superseded
        }
        if (receipt.state !in setOf(MemoryMutationState.INDEX_PENDING, MemoryMutationState.INDEXED)) {
            return CurrentSyncState.Retryable("vector_index_receipt_not_ready")
        }
        return CurrentSyncState.Current(corpus, receipt)
    }

    private suspend fun currentSnapshot(payload: MemoryIndexSyncJobPayload): MemoryCorpusSnapshot? {
        val snapshots = snapshotSource.snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM).getOrThrow()
        val snapshot = snapshots.singleOrNull() ?: return null
        return snapshot.takeIf { current ->
            current.sourcePath == payload.sourcePath && current.sourceHash == payload.sourceHash
        }
    }

    private fun manifestStatus(
        manifest: MemoryVectorManifest?,
        payload: MemoryIndexSyncJobPayload
    ): ManifestStatus {
        manifest ?: return ManifestStatus.REBUILD
        val identity = manifest.identity
        if (identity.corpusGeneration > payload.generation) return ManifestStatus.SUPERSEDED
        if (identity.corpusGeneration < payload.generation) return ManifestStatus.REBUILD
        if (
            identity.corpus != MemoryCorpus.CHAT_RECALL_LONG_TERM ||
            identity.sourcePath != payload.sourcePath ||
            identity.sourceHash != payload.sourceHash ||
            identity.indexFingerprint != payload.targetIndexFingerprint
        ) {
            return ManifestStatus.CONFLICT
        }
        return ManifestStatus.CONFLICT
    }

    private fun MemoryIndexSyncJobPayload.toExpectation(
        chunks: List<MemoryCorpusChunk>
    ): MemoryVectorSnapshotExpectation =
        MemoryVectorSnapshotExpectation(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = sourcePath,
            sourceHash = sourceHash,
            corpusGeneration = generation,
            indexFingerprint = targetIndexFingerprint,
            chunks = chunks
        )

    private suspend fun embedDocuments(
        snapshot: MemoryCorpusSnapshot,
        capability: MemoryEmbeddingCapability.Ready,
        configuration: MemoryVectorIndexConfiguration
    ): List<FloatArray>? {
        val embeddings = ArrayList<FloatArray>(snapshot.chunks.size)
        snapshot.chunks.chunked(EMBEDDING_BATCH_SIZE).forEach { chunks ->
            val batch = capability.provider.embedDocuments(chunks.map(MemoryCorpusChunk::text)).getOrThrow()
            if (batch.size != chunks.size) return null
            embeddings += batch
        }
        return embeddings.takeIf { vectors ->
            vectors.size == snapshot.chunks.size && vectors.all { vector ->
                vector.isValidFor(configuration)
            }
        }
    }

    private fun FloatArray.isValidFor(configuration: MemoryVectorIndexConfiguration): Boolean {
        if (size != configuration.embeddingDescriptor.dimension || any { value -> !value.isFinite() }) {
            return false
        }
        if (!configuration.embeddingDescriptor.normalized) return true
        val norm = sqrt(sumOf { value -> value.toDouble() * value.toDouble() })
        return abs(norm - 1.0) <= NORMALIZED_VECTOR_TOLERANCE
    }

    private suspend fun completeRoomPublication(
        payload: MemoryIndexSyncJobPayload,
        originalReceipt: MemoryMutationReceipt
    ): MemoryIndexSyncResult {
        return when (
            recoveryDao.completeVectorIndexPublication(
                MemoryVectorIndexPublicationRequest(
                    corpus = CHAT_RECALL_CORPUS_KEY,
                    mutationGroupId = payload.mutationGroupId,
                    receiptId = payload.receiptId,
                    generation = payload.generation,
                    sourcePath = payload.sourcePath,
                    sourceHash = payload.sourceHash,
                    targetIndexFingerprint = payload.targetIndexFingerprint,
                    completedAt = clock.instant().epochSecond
                )
            )
        ) {
            MemoryVectorIndexPublicationOutcome.COMPLETED,
            MemoryVectorIndexPublicationOutcome.ALREADY_COMPLETE -> {
                memoryFileStore.cleanupStagedTarget(originalReceipt.stagedTargetPath).getOrThrow()
                MemoryIndexSyncResult.Succeeded
            }
            MemoryVectorIndexPublicationOutcome.SUPERSEDED -> MemoryIndexSyncResult.Superseded
            MemoryVectorIndexPublicationOutcome.CONFLICT ->
                MemoryIndexSyncResult.Terminal("room_vector_publication_conflict")
        }
    }

    private suspend fun latestGenerationResult(
        payload: MemoryIndexSyncJobPayload,
        retryReason: String
    ): MemoryIndexSyncResult =
        if ((recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)?.generation ?: 0L) > payload.generation) {
            MemoryIndexSyncResult.Superseded
        } else {
            MemoryIndexSyncResult.Retryable(retryReason)
        }

    private suspend fun availabilityResult(
        payload: MemoryIndexSyncJobPayload,
        availability: MemoryEmbeddingAvailability
    ): MemoryIndexSyncResult = when (availability) {
        MemoryEmbeddingAvailability.Available ->
            MemoryIndexSyncResult.Terminal("available_embedding_has_no_provider")
        MemoryEmbeddingAvailability.Loading -> MemoryIndexSyncResult.Retryable("embedding_provider_loading")
        is MemoryEmbeddingAvailability.Unavailable ->
            markBlockedDependency(payload, "embedding_unavailable:${availability.reason.name.lowercase()}")
    }

    private suspend fun markBlockedDependency(
        payload: MemoryIndexSyncJobPayload,
        reason: String
    ): MemoryIndexSyncResult {
        val current = recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)
            ?: return MemoryIndexSyncResult.Retryable("memory_corpus_state_missing")
        if (current.generation > payload.generation) return MemoryIndexSyncResult.Superseded
        if (current.generation < payload.generation || !current.matches(payload)) {
            return MemoryIndexSyncResult.Terminal("room_corpus_changed_before_dependency_block")
        }
        val changed = recoveryDao.transitionCorpusIndexStatusCas(
            corpus = CHAT_RECALL_CORPUS_KEY,
            expectedGeneration = payload.generation,
            expectedSourceHash = payload.sourceHash,
            expectedTargetIndexFingerprint = payload.targetIndexFingerprint,
            expectedIndexStatus = current.indexStatus,
            expectedRowVersion = current.rowVersion,
            newIndexStatus = MemoryCorpusIndexStatus.BLOCKED_DEPENDENCY,
            lastError = reason,
            updatedAt = clock.instant().epochSecond
        )
        return if (changed == 1) {
            MemoryIndexSyncResult.BlockedDependency(reason)
        } else {
            latestGenerationResult(payload, "room_corpus_changed_before_dependency_block")
        }
    }

    private fun MemoryCorpusState.matches(payload: MemoryIndexSyncJobPayload): Boolean =
        sourcePath == payload.sourcePath &&
            sourceHash == payload.sourceHash &&
            targetIndexFingerprint == payload.targetIndexFingerprint &&
            latestReceiptId == payload.receiptId

    private fun recoverDerivedStore(throwable: Throwable): Boolean =
        runCatching { vectorStore.recoverFromCorruption(throwable) }.getOrDefault(false)

    private sealed interface CurrentSyncState {
        data class Current(
            val corpus: MemoryCorpusState,
            val receipt: MemoryMutationReceipt
        ) : CurrentSyncState

        data object Superseded : CurrentSyncState

        data class Retryable(val reason: String) : CurrentSyncState

        data class Conflict(val reason: String) : CurrentSyncState
    }

    private enum class ManifestStatus {
        REBUILD,
        SUPERSEDED,
        CONFLICT
    }

    private companion object {
        const val EMBEDDING_BATCH_SIZE = 32
        const val NORMALIZED_VECTOR_TOLERANCE = 1e-3
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
        val CHAT_RECALL_CORPUS_KEY = MemoryCorpus.CHAT_RECALL_LONG_TERM.name.lowercase()
    }
}

fun interface MemoryIndexSyncService {
    suspend fun synchronize(job: MemoryMaintenanceJob): MemoryIndexSyncResult
}

sealed interface MemoryIndexSyncResult {
    data object Succeeded : MemoryIndexSyncResult

    data object Superseded : MemoryIndexSyncResult

    data class Retryable(val reason: String) : MemoryIndexSyncResult

    data class BlockedDependency(val reason: String) : MemoryIndexSyncResult

    data class Terminal(val reason: String) : MemoryIndexSyncResult
}
