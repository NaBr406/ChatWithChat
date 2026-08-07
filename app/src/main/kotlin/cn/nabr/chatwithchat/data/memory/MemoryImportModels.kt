package cn.nabr.chatwithchat.data.memory

import kotlinx.serialization.Serializable

/** Input sent to the memory model when an external memory document is imported. */
@Serializable
data class MemoryImportRequest(
    val importedText: String,
    val existingMemories: List<MemoryBatchExistingMemory>
)

@Serializable
data class MemoryImportProposal(
    val operations: List<MemoryImportOperation> = emptyList()
)

@Serializable
data class MemoryImportOperation(
    val action: String,
    val targetMemoryId: String? = null,
    val text: String = "",
    val type: String = "stable_profile",
    val sensitivity: String = MemorySensitivity.NORMAL,
    val source: String = MemorySource.EXPLICIT_USER_STATEMENT,
    val canonicalKey: String? = null,
    val scope: String? = null,
    val recallState: String? = null,
    val reason: String = ""
)

object MemoryImportAction {
    const val CREATE = "create"
    const val REPLACE = "replace"
    const val IGNORE = "ignore"
}

sealed interface MemoryImportOutcome {
    data class Imported(
        val importedCount: Int,
        val skippedCount: Int = 0,
        val rewrittenByModel: Boolean
    ) : MemoryImportOutcome
}

class MemoryImportException(
    val reason: Reason,
    cause: Throwable? = null
) : IllegalStateException(reason.code, cause) {
    enum class Reason(val code: String) {
        EMPTY_INPUT("empty_input"),
        INPUT_TOO_LARGE("input_too_large"),
        INVALID_APP_FORMAT("invalid_app_format"),
        CURRENT_MEMORY_INVALID("current_memory_invalid"),
        MODEL_UNAVAILABLE("model_unavailable"),
        MODEL_REWRITE_FAILED("model_rewrite_failed"),
        CONFLICT("conflict"),
        WRITE_FAILED("write_failed")
    }
}
