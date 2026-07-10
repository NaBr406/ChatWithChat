package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class ConversationClassificationRequest(
    val chatTitle: String,
    val recentMessages: List<MemoryConversationMessage>
)

@Serializable
data class ConversationClassificationResult(
    val mode: String,
    val intent: String,
    val memoryNeeds: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
    val emotionalTone: String? = null,
    val shouldUseMemories: Boolean = false,
    val shouldLearnMemories: Boolean = false,
    val sensitivity: String = MemorySensitivity.NORMAL,
    val confidence: Float = 0f
)

@Serializable
data class MemorySelectionRequest(
    val classification: ConversationClassificationResult,
    val currentUserMessage: String,
    val candidateMemories: List<MemorySelectionCandidate>
)

@Serializable
data class MemorySelectionCandidate(
    val memoryId: Int,
    val summary: String,
    val recallText: String,
    val type: String,
    val status: String,
    val source: String,
    val sensitivity: String,
    val importance: Float,
    val confidence: Float
)

@Serializable
data class MemorySelectionResult(
    val selected: List<SelectedMemoryReference> = emptyList()
)

@Serializable
data class SelectedMemoryReference(
    val memoryId: Int,
    val relevance: Float,
    val usage: String,
    val reason: String
)

@Serializable
data class MemoryExtractionRequest(
    val chatTitle: String,
    val recentMessages: List<MemoryConversationMessage>
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MemoryCandidate(
    val summary: String = "",
    val details: String? = null,
    @JsonNames("recall_text", "recall")
    val recallText: String = "",
    val type: String = "stable_profile",
    val scope: String = "personal",
    val domains: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @JsonNames("applicable_modes")
    val applicableModes: List<String> = emptyList(),
    @JsonNames("avoid_modes")
    val avoidModes: List<String> = emptyList(),
    val importance: Float = 0f,
    val confidence: Float = 0f,
    val source: String = MemorySource.ASSISTANT_INFERRED,
    val sensitivity: String = MemorySensitivity.NORMAL,
    @JsonNames("suggested_status", "status")
    val suggestedStatus: String = MemoryStatus.PENDING_CONFIRMATION,
    val evidence: String? = null,
    @JsonNames("requires_confirmation")
    val requiresConfirmation: Boolean = true,
    val reason: String = ""
)

@Serializable
data class MemoryUpdatePlanningRequest(
    val candidates: List<MemoryCandidate>,
    val existingMemories: List<MemorySelectionCandidate>
)

@Serializable
data class MemoryUpdatePlan(
    val operations: List<MemoryUpdateOperation> = emptyList()
)

@Serializable
data class MemoryUpdateOperation(
    val action: String,
    val targetMemoryIds: List<Int> = emptyList(),
    val candidateIndex: Int? = null,
    val result: MemoryCandidate? = null,
    val reason: String
)

@Serializable
data class MemoryConversationMessage(
    val role: String,
    val content: String
)

data class SelectedPersonalMemory(
    val memory: PersonalMemory,
    val usage: String,
    val relevance: Float,
    val reason: String
)

data class PreparedMemoryContext(
    val classification: ConversationClassificationResult? = null,
    val selectedMemories: List<SelectedPersonalMemory> = emptyList(),
    val selectedMarkdownMemories: List<MemoryIndexSearchResult> = emptyList(),
    val retrievedMemories: List<MemoryRetrievalResult> = emptyList(),
    val prompt: String? = null
)

data class MemoryLearningResult(
    val status: String,
    val completed: Boolean,
    val createdCount: Int = 0,
    val updatedCount: Int = 0,
    val statusChangedCount: Int = 0,
    val candidateCount: Int = 0,
    val operationCount: Int = 0,
    val reason: String? = null
) {
    val changedCount: Int
        get() = createdCount + updatedCount + statusChangedCount

    val shouldRememberSignature: Boolean
        get() = completed

    companion object {
        const val STATUS_APPLIED = "applied"
        const val STATUS_APPLIED_DIRECT_FALLBACK = "applied_direct_fallback"
        const val STATUS_SKIPPED_NO_MESSAGES = "skipped_no_messages"
        const val STATUS_FAILED_CLASSIFICATION_UNAVAILABLE = "failed_classification_unavailable"
        const val STATUS_SKIPPED_SHOULD_LEARN_FALSE = "skipped_should_learn_false"
        const val STATUS_FAILED_EXTRACTION_UNAVAILABLE = "failed_extraction_unavailable"
        const val STATUS_SKIPPED_NO_CANDIDATES = "skipped_no_candidates"
        const val STATUS_FAILED_PLAN_UNAVAILABLE = "failed_plan_unavailable"
        const val STATUS_SKIPPED_NO_OPERATIONS = "skipped_no_operations"
        const val STATUS_FAILED_EXCEPTION = "failed_exception"

        fun applied(
            createdCount: Int,
            updatedCount: Int,
            statusChangedCount: Int,
            candidateCount: Int,
            operationCount: Int,
            directFallback: Boolean = false
        ): MemoryLearningResult = MemoryLearningResult(
            status = if (directFallback) STATUS_APPLIED_DIRECT_FALLBACK else STATUS_APPLIED,
            completed = true,
            createdCount = createdCount,
            updatedCount = updatedCount,
            statusChangedCount = statusChangedCount,
            candidateCount = candidateCount,
            operationCount = operationCount
        )

        fun skipped(
            status: String,
            candidateCount: Int = 0,
            operationCount: Int = 0,
            reason: String? = null
        ): MemoryLearningResult = MemoryLearningResult(
            status = status,
            completed = true,
            candidateCount = candidateCount,
            operationCount = operationCount,
            reason = reason
        )

        fun failed(
            status: String,
            candidateCount: Int = 0,
            operationCount: Int = 0,
            reason: String? = null
        ): MemoryLearningResult = MemoryLearningResult(
            status = status,
            completed = false,
            candidateCount = candidateCount,
            operationCount = operationCount,
            reason = reason
        )

        fun failedException(throwable: Throwable): MemoryLearningResult = failed(
            status = STATUS_FAILED_EXCEPTION,
            reason = throwable.message ?: throwable.javaClass.simpleName
        )
    }
}

object MemoryStatus {
    const val ACTIVE = "active"
    const val PENDING_CONFIRMATION = "pending_confirmation"
    const val RESOLVED = "resolved"
    const val ARCHIVED = "archived"
    const val SUPERSEDED = "superseded"
}

object MemorySensitivity {
    const val NORMAL = "normal"
    const val PRIVATE = "private"
    const val SENSITIVE = "sensitive"
}

object MemorySource {
    const val EXPLICIT_USER_STATEMENT = "explicit_user_statement"
    const val ASSISTANT_INFERRED = "assistant_inferred"
    const val USER_CONFIRMED = "user_confirmed"
}

object MemoryUsage {
    const val TONE_ONLY = "tone_only"
    const val IMPLICIT_CONTEXT = "implicit_context"
    const val EXPLICIT_IF_NATURAL = "explicit_if_natural"
}

object MemoryAction {
    const val CREATE = "create"
    const val UPDATE = "update"
    const val MERGE = "merge"
    const val MARK_RESOLVED = "mark_resolved"
    const val ARCHIVE = "archive"
    const val IGNORE = "ignore"
}

fun PersonalMemory.toSelectionCandidate(): MemorySelectionCandidate = MemorySelectionCandidate(
    memoryId = id,
    summary = summary,
    recallText = recallText,
    type = type,
    status = status,
    source = source,
    sensitivity = sensitivity,
    importance = importance,
    confidence = confidence
)

fun MemoryCandidate.toPersonalMemory(
    id: Int = 0,
    now: Long = System.currentTimeMillis() / 1000,
    status: String = suggestedStatus
): PersonalMemory = PersonalMemory(
    id = id,
    summary = summary.trim(),
    details = details?.trim()?.takeIf { it.isNotBlank() },
    recallText = recallText.trim(),
    type = type,
    scope = scope,
    domains = domains,
    entities = entities,
    tags = tags,
    applicableModes = applicableModes,
    avoidModes = avoidModes,
    importance = importance.coerceIn(0f, 1f),
    confidence = confidence.coerceIn(0f, 1f),
    source = source,
    sensitivity = sensitivity,
    status = status,
    evidence = evidence?.trim()?.takeIf { it.isNotBlank() },
    createdAt = now,
    updatedAt = now
)

fun buildMemoryMessages(
    chatRoom: ChatRoomV2,
    userMessages: List<MessageV2>,
    assistantMessages: List<List<MessageV2>>,
    maxTurns: Int = 6
): List<MemoryConversationMessage> {
    val startIndex = (userMessages.size - maxTurns).coerceAtLeast(0)
    val messages = mutableListOf<MemoryConversationMessage>()
    userMessages.drop(startIndex).forEachIndexed { offset, userMessage ->
        val turnIndex = startIndex + offset
        messages.add(MemoryConversationMessage(role = "user", content = userMessage.content))
        assistantMessages.getOrNull(turnIndex)
            ?.firstOrNull { it.content.isNotBlank() }
            ?.let { assistantMessage ->
                messages.add(MemoryConversationMessage(role = "assistant", content = assistantMessage.content))
            }
    }

    if (messages.isEmpty() && chatRoom.title.isNotBlank()) {
        return listOf(MemoryConversationMessage(role = "system_context", content = "Chat title: ${chatRoom.title}"))
    }

    return messages
}
