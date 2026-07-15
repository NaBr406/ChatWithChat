package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.MemoryMutationGroup
import cn.nabr.chatwithchat.data.database.entity.MemoryMutationReceipt
import kotlinx.serialization.Serializable

data class MemoryMutationTarget(
    val sourcePath: String,
    val baseContent: String,
    val targetContent: String,
    val targetIndexFingerprint: String?
)

data class MemoryPreparedMutation(
    val group: MemoryMutationGroup,
    val receipts: List<MemoryMutationReceipt>
)

sealed interface MemoryMutationCommitResult {
    data class CanonicalCommitted(
        val mutation: MemoryPreparedMutation,
        val hasPendingIndex: Boolean,
        val requiresSemanticAcknowledgement: Boolean
    ) : MemoryMutationCommitResult

    data class Conflict(
        val mutation: MemoryPreparedMutation,
        val sourcePath: String,
        val reason: String,
        val requiresSemanticFinalization: Boolean
    ) : MemoryMutationCommitResult
}

object MemoryMutationState {
    const val PREPARED = "prepared"
    const val FILE_COMMITTED = "file_committed"
    const val SEMANTIC_ACK_PENDING = "semantic_ack_pending"
    const val INDEX_PENDING = "index_pending"
    const val INDEXED = "indexed"
    const val SUPERSEDED = "superseded"
    const val CONFLICT = "conflict"
    const val FAILED = "failed"

    val INCOMPLETE = listOf(PREPARED, FILE_COMMITTED, SEMANTIC_ACK_PENDING, INDEX_PENDING, FAILED)
}

data class MemoryRecoveredSemanticMutation(
    val groupId: String,
    val semanticJobId: String,
    val generation: Long,
    val terminalReason: String? = null
)

object MemoryCorpusIndexStatus {
    const val PENDING = "pending"
    const val READY = "ready"
    const val BLOCKED_DEPENDENCY = "blocked_dependency"
    const val WAITING_REPAIR = "waiting_repair"
}

@Serializable
data class MemoryIndexSyncJobPayload(
    val mutationGroupId: String,
    val receiptId: String,
    val generation: Long,
    val sourcePath: String,
    val sourceHash: String,
    val targetIndexFingerprint: String
)
