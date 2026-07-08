package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class MarkdownMemoryLearningRequest(
    val chatId: Int,
    val chatTitle: String,
    val recentMessages: List<MemoryConversationMessage>,
    val existingMarkdownMemories: List<MarkdownMemoryLearningExistingMemory> = emptyList(),
    val existingRoomMemories: List<MarkdownMemoryLearningExistingMemory> = emptyList()
)

@Serializable
data class MarkdownMemoryLearningExistingMemory(
    val text: String,
    val type: String,
    val sensitivity: String,
    val source: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MarkdownMemoryLearningProposal(
    @JsonNames("daily_notes")
    val dailyNotes: List<MarkdownMemoryLearningNote> = emptyList(),
    @JsonNames("long_term_updates")
    val longTermUpdates: List<MarkdownMemoryLearningNote> = emptyList()
)

@Serializable
data class MarkdownMemoryLearningNote(
    val text: String = "",
    val type: String = "stable_profile",
    val sensitivity: String = MemorySensitivity.NORMAL,
    val source: String = MemorySource.ASSISTANT_INFERRED,
    val reason: String = ""
)

@Serializable
data class MarkdownMemoryLearningJobPayload(
    val chatId: Int,
    val chatTitle: String,
    val learningKey: String,
    val recentMessages: List<MemoryConversationMessage>,
    val createdAt: Long
)

data class MarkdownMemoryLearningResult(
    val status: String,
    val dailyNotesWritten: Int = 0,
    val longTermUpdatesWritten: Int = 0,
    val duplicateCount: Int = 0,
    val jobId: String? = null,
    val reason: String? = null
) {
    val changedCount: Int
        get() = dailyNotesWritten + longTermUpdatesWritten

    companion object {
        const val STATUS_APPLIED = "applied"
        const val STATUS_SKIPPED_DUPLICATE_JOB = "skipped_duplicate_job"
        const val STATUS_SKIPPED_ALREADY_RUNNING = "skipped_already_running"
        const val STATUS_SKIPPED_NO_PROPOSAL = "skipped_no_proposal"
        const val STATUS_SKIPPED_NO_NOTES = "skipped_no_notes"
        const val STATUS_FAILED_RETRYABLE = "failed_retryable"
        const val STATUS_FAILED_TERMINAL = "failed_terminal"

        fun skipped(
            status: String,
            jobId: String? = null,
            reason: String? = null
        ): MarkdownMemoryLearningResult = MarkdownMemoryLearningResult(
            status = status,
            jobId = jobId,
            reason = reason
        )
    }
}
