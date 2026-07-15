package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.entity.MemoryCorpusState
import cn.nabr.chatwithchat.data.database.entity.MemoryMutationGroup
import cn.nabr.chatwithchat.data.database.entity.MemoryMutationReceipt
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults

class MemoryVectorIndexBootstrapService(
    private val recoveryDao: MemoryRecoveryDao,
    private val memoryFileStore: MemoryFileStore,
    private val mutationCoordinator: MemoryMutationCoordinator,
    private val targetIndexFingerprint: String = MemoryVectorIndexDefaults.configuration.fingerprint()
) {
    init {
        require(SHA_256_REGEX.matches(targetIndexFingerprint)) {
            "Bootstrap index fingerprint must be SHA-256"
        }
    }

    suspend fun bootstrap(): MemoryVectorIndexBootstrapResult {
        val sourceContent = memoryFileStore.readLongTermMemory().getOrThrow()
        val sourceHash = sourceContent.toByteArray(Charsets.UTF_8).sha256Hex()
        val current = recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)

        current?.matchingMutation(sourceHash)?.let { mutation ->
            if (mutation.needsReconciliation()) {
                return mutationCoordinator.reconcile(mutation).toBootstrapResult()
            }
            return MemoryVectorIndexBootstrapResult.AlreadyCurrent(
                generation = current.generation,
                sourceHash = sourceHash,
                indexFingerprint = targetIndexFingerprint,
                indexStatus = current.indexStatus
            )
        }

        val prepared = mutationCoordinator.prepareLocalIndexBootstrap(
            sourceContent = sourceContent,
            sourceHash = sourceHash,
            targetIndexFingerprint = targetIndexFingerprint,
            observedCorpusGeneration = current?.generation ?: 0L
        )
        return mutationCoordinator.reconcile(prepared).toBootstrapResult()
    }

    private suspend fun MemoryCorpusState.matchingMutation(sourceHash: String): MemoryPreparedMutation? {
        if (
            corpus != CHAT_RECALL_CORPUS_KEY ||
            sourcePath != MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME ||
            this.sourceHash != sourceHash ||
            targetIndexFingerprint != this@MemoryVectorIndexBootstrapService.targetIndexFingerprint
        ) {
            return null
        }
        val receipt = latestReceiptId?.let { receiptId -> recoveryDao.getMutationReceipt(receiptId) } ?: return null
        val group = recoveryDao.getMutationGroup(receipt.groupId) ?: return null
        if (!matches(group, receipt)) return null
        return MemoryPreparedMutation(group, recoveryDao.getMutationReceipts(group.groupId))
    }

    private fun MemoryCorpusState.matches(
        group: MemoryMutationGroup,
        receipt: MemoryMutationReceipt
    ): Boolean =
        group.generation == generation &&
            receipt.groupId == group.groupId &&
            receipt.generation == generation &&
            receipt.receiptId == latestReceiptId &&
            receipt.sourcePath == sourcePath &&
            receipt.targetSourceHash == sourceHash &&
            receipt.targetIndexFingerprint == targetIndexFingerprint &&
            receipt.state in DURABLE_RECEIPT_STATES &&
            group.state in DURABLE_GROUP_STATES

    private fun MemoryPreparedMutation.needsReconciliation(): Boolean =
        group.state in RECONCILABLE_GROUP_STATES ||
            receipts.any { receipt -> receipt.state in RECONCILABLE_RECEIPT_STATES }

    private suspend fun MemoryMutationCommitResult.toBootstrapResult(): MemoryVectorIndexBootstrapResult = when (this) {
        is MemoryMutationCommitResult.CanonicalCommitted -> {
            val corpus = checkNotNull(recoveryDao.getCorpusState(CHAT_RECALL_CORPUS_KEY)) {
                "Bootstrap mutation committed without durable corpus state"
            }
            MemoryVectorIndexBootstrapResult.Scheduled(
                generation = mutation.group.generation,
                sourceHash = corpus.sourceHash,
                indexFingerprint = checkNotNull(corpus.targetIndexFingerprint),
                indexStatus = corpus.indexStatus
            )
        }
        is MemoryMutationCommitResult.Conflict -> MemoryVectorIndexBootstrapResult.Conflict(
            generation = mutation.group.generation,
            sourcePath = sourcePath,
            reason = mutation.group.lastError ?: "memory_vector_bootstrap_conflict"
        )
    }

    private companion object {
        val CHAT_RECALL_CORPUS_KEY = MemoryCorpus.CHAT_RECALL_LONG_TERM.name.lowercase()
        val DURABLE_RECEIPT_STATES = setOf(
            MemoryMutationState.PREPARED,
            MemoryMutationState.FILE_COMMITTED,
            MemoryMutationState.INDEX_PENDING,
            MemoryMutationState.INDEXED
        )
        val DURABLE_GROUP_STATES = setOf(
            MemoryMutationState.PREPARED,
            MemoryMutationState.FILE_COMMITTED,
            MemoryMutationState.FAILED,
            MemoryMutationState.SEMANTIC_ACK_PENDING,
            MemoryMutationState.INDEX_PENDING,
            MemoryMutationState.INDEXED
        )
        val RECONCILABLE_RECEIPT_STATES = setOf(
            MemoryMutationState.PREPARED,
            MemoryMutationState.FILE_COMMITTED
        )
        val RECONCILABLE_GROUP_STATES = setOf(
            MemoryMutationState.PREPARED,
            MemoryMutationState.FILE_COMMITTED,
            MemoryMutationState.FAILED
        )
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}

sealed interface MemoryVectorIndexBootstrapResult {
    data class AlreadyCurrent(
        val generation: Long,
        val sourceHash: String,
        val indexFingerprint: String,
        val indexStatus: String
    ) : MemoryVectorIndexBootstrapResult

    data class Scheduled(
        val generation: Long,
        val sourceHash: String,
        val indexFingerprint: String,
        val indexStatus: String
    ) : MemoryVectorIndexBootstrapResult

    data class Conflict(
        val generation: Long,
        val sourcePath: String,
        val reason: String
    ) : MemoryVectorIndexBootstrapResult
}
