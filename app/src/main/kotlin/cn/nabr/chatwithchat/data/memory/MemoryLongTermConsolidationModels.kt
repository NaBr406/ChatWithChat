package cn.nabr.chatwithchat.data.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemoryLongTermConsolidationJobPayload(
    val checkpointId: String,
    val baseSourceHash: String,
    val orderedSnapshotHash: String
)

@Serializable
data class MemoryLongTermConsolidationPartitionRequest(
    val checkpointId: String,
    val partitionStart: Int,
    val partitionEndExclusive: Int,
    val candidateGroups: List<MemoryLongTermCandidateGroup>
)

@Serializable
data class MemoryLongTermCandidateGroup(
    val groupId: String,
    val anchorMemoryIds: List<String>,
    val entries: List<MemoryLongTermCandidateEntry>
)

@Serializable
data class MemoryLongTermCandidateEntry(
    val memoryId: String,
    val text: String,
    val type: String,
    val source: String,
    val canonicalKey: String? = null,
    val scope: String,
    val lastObservedAt: Long,
    val recallState: String
)

@Serializable
data class MemoryLongTermConsolidationProposal(
    val decisions: List<MemoryLongTermCanonicalDecision> = emptyList()
)

@Serializable
data class MemoryLongTermCanonicalDecision(
    val action: String,
    val memoryIds: List<String> = emptyList(),
    val canonicalKey: String? = null,
    val scope: String? = null,
    val recallState: String? = null,
    val reason: String = ""
)

@Serializable
data class MemoryLongTermPersistedProposal(
    val decisions: List<MemoryLongTermCanonicalDecision> = emptyList()
)

object MemoryLongTermDecisionAction {
    const val CANONICALIZE = "canonicalize"
    const val RETIRE = "retire"
    const val IGNORE = "ignore"
}

object MemoryLongTermCheckpointStatus {
    const val PENDING = "pending"
    const val PREPARED = "prepared"
    const val COMPLETED = "completed"
    const val STALE_SOURCE = "stale_source"
    const val CONFLICT = "conflict"
    const val BLOCKED = "blocked"
    const val DISMISSED = "dismissed"

    val ACTIVE = listOf(PENDING, PREPARED)
    val TERMINAL = setOf(COMPLETED, STALE_SOURCE, CONFLICT, BLOCKED, DISMISSED)
}

object MemoryLongTermTriggerReason {
    const val MATERIAL_THRESHOLD = "material_threshold"
    const val WEEKLY_DUE = "weekly_due"
    const val CONTINUATION = "continuation"
    const val MANUAL = "manual"
    const val MANUAL_FORCE = "manual_force"
}

data class MemoryLongTermPlanResult(
    val scheduled: Boolean,
    val checkpointId: String? = null,
    val jobId: String? = null,
    val reason: String
)

data class MemoryLongTermProcessResult(
    val status: String,
    val jobId: String,
    val operationCount: Int = 0,
    val reason: String? = null
) {
    companion object {
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_DUPLICATE = "duplicate"
        const val STATUS_DEFERRED = "deferred"
        const val STATUS_RETRYABLE = "retryable"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_TERMINAL = "terminal"
    }
}
