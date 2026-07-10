package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemoryBatchConsolidationRequest(
    val batchId: String,
    val chatId: Int,
    val chatTitle: String,
    val triggerReason: String,
    val turns: List<MemoryCompletedTurnSnapshot>,
    val existingMemories: List<MemoryBatchExistingMemory>
)

@Serializable
data class MemoryBatchExistingMemory(
    val id: String,
    val sourcePath: String,
    val text: String,
    val type: String,
    val sensitivity: String,
    val source: String,
    val updatedAt: Long
)

@Serializable
data class MemoryBatchConsolidationProposal(
    val operations: List<MemoryBatchOperation> = emptyList()
)

@Serializable
data class MemoryBatchOperation(
    val destination: String,
    val action: String,
    val targetMemoryId: String? = null,
    val text: String = "",
    val type: String = "stable_profile",
    val sensitivity: String = MemorySensitivity.NORMAL,
    val source: String = MemorySource.ASSISTANT_INFERRED,
    val evidenceTurnKeys: List<String> = emptyList(),
    val reason: String = ""
)

object MemoryBatchDestination {
    const val DAILY = "daily"
    const val LONG_TERM = "long_term"
}

object MemoryBatchAction {
    const val CREATE = "create"
    const val REPLACE = "replace"
    const val REMOVE = "remove"
    const val IGNORE = "ignore"
}

data class MemoryBatchProcessResult(
    val status: String,
    val jobId: String,
    val operationCount: Int = 0,
    val dailyWriteCount: Int = 0,
    val longTermWriteCount: Int = 0,
    val reason: String? = null
) {
    companion object {
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_RETRYABLE = "retryable"
        const val STATUS_TERMINAL = "terminal"
        const val STATUS_DUPLICATE = "duplicate"
    }
}
