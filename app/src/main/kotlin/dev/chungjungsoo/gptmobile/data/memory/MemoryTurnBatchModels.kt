package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.Serializable

data class MemoryCompletedTurnInput(
    val chatRoom: ChatRoomV2,
    val userMessage: MessageV2,
    val assistantMessages: List<MessageV2>,
    val preferredPlatformUid: String?,
    val stablePlatformOrder: List<String>,
    val completedAt: Long
)

@Serializable
data class MemoryCompletedTurnSnapshot(
    val turnKey: String,
    val chatId: Int,
    val chatTitle: String,
    val userMessageId: Int,
    val userContent: String,
    val userAttachments: List<MemoryAttachmentSnapshot>,
    val assistantPlatformUid: String,
    val assistantContent: String,
    val completedAt: Long
)

@Serializable
data class MemoryAttachmentSnapshot(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

data class MemoryTurnRecordingResult(
    val recorded: Boolean,
    val turnKey: String? = null,
    val pendingCount: Int = 0,
    val reason: String? = null
) {
    companion object {
        fun skipped(reason: String): MemoryTurnRecordingResult = MemoryTurnRecordingResult(
            recorded = false,
            reason = reason
        )
    }
}

data class MemoryPendingTurnState(
    val chatId: Int,
    val pendingCount: Int,
    val idleDueAt: Long?,
    val thresholdEligible: Boolean
)

fun interface MemoryPendingTurnObserver {
    suspend fun onPendingTurnStateChanged(state: MemoryPendingTurnState)
}

object NoOpMemoryPendingTurnObserver : MemoryPendingTurnObserver {
    override suspend fun onPendingTurnStateChanged(state: MemoryPendingTurnState) = Unit
}
