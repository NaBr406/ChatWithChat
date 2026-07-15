package cn.nabr.chatwithchat.data.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemoryDailyDistillationFrozenInput(
    val batchId: String,
    val batchKey: String,
    val dailySourcePath: String,
    val dailySourceHash: String,
    val dailyDate: String,
    val dailyEvidence: List<MemoryDailyDistillationEvidence>,
    val existingMemories: List<MemoryBatchExistingMemory>,
    val targetBaseHash: String,
    val createdAt: Long
)

@Serializable
data class MemoryDailyDistillationEvidence(
    val evidenceKey: String,
    val entryId: String,
    val text: String,
    val type: String,
    val sensitivity: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class MemoryDailyDistillationJobPayload(
    val checkpointId: String,
    val input: MemoryDailyDistillationFrozenInput,
    val inputHash: String
)

@Serializable
data class MemoryDailyDistillationPlanJobPayload(
    val kind: String,
    val localDate: String,
    val dailySourcePath: String? = null,
    val dailySourceHash: String? = null,
    val batchKey: String? = null,
    val targetBaseHash: String? = null
)

object MemoryDailyDistillationPlanKind {
    const val BATCH = "batch"
    const val DAILY_WAKE = "daily_wake"
}

@Serializable
data class MemoryDailyDistillationProposal(
    val operations: List<MemoryDailyDistillationOperation> = emptyList()
)

@Serializable
data class MemoryDailyDistillationOperation(
    val action: String,
    val targetMemoryId: String? = null,
    val text: String = "",
    val type: String = "stable_profile",
    val sensitivity: String = MemorySensitivity.NORMAL,
    val source: String = MemorySource.ASSISTANT_INFERRED,
    val evidenceKeys: List<String> = emptyList(),
    val reason: String = ""
)

object MemoryDailyDistillationAction {
    const val CREATE = "create"
    const val REPLACE = "replace"
    const val IGNORE = "ignore"
}

object MemoryDistillationCheckpointStatus {
    const val PENDING = "pending"
    const val PREPARED = "prepared"
    const val COMPLETED = "completed"
    const val STALE_SOURCE = "stale_source"
    const val STALE_TARGET_BASE = "stale_target_base"
    const val CONFLICT = "conflict"

    val REPLANNABLE = setOf(STALE_SOURCE, STALE_TARGET_BASE)
    val RECOVERABLE = setOf(PENDING, PREPARED)
}

data class MemoryDailyDistillationPlanResult(
    val scheduledJobId: String? = null,
    val scheduledCheckpointId: String? = null,
    val completedBatchCount: Int = 0,
    val nextDailyPlanAt: Long? = null
)

data class MemoryDailyDistillationProcessResult(
    val status: String,
    val jobId: String,
    val operationCount: Int = 0,
    val reason: String? = null
) {
    companion object {
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_DUPLICATE = "duplicate"
        const val STATUS_RETRYABLE = "retryable"
        const val STATUS_TERMINAL = "terminal"
    }
}
