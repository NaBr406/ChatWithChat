package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
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

@Serializable
data class MemoryTurnBatchJobPayload(
    val batchId: String,
    val chatId: Int,
    val triggerReason: String,
    val turns: List<MemoryTurnBatchJobTurn>,
    val contentHash: String,
    val createdAt: Long
)

@Serializable
data class MemoryTurnBatchJobTurn(
    val turnKey: String,
    val userMessageId: Int,
    val payloadJson: String,
    val contentHash: String
)

object MemoryTurnBatchTriggerReason {
    const val THRESHOLD = "threshold"
    const val IDLE = "idle"
    const val CONTEXT_COMPACTION = "context_compaction"
    const val MANUAL_RETRY = "manual_retry"
    const val LEGACY_APPEND_DAILY_NOTE = "legacy_append_daily_note"
    const val LEGACY_COMPACTION_FLUSH = "legacy_compaction_flush"
}

fun interface MemoryPendingTurnObserver {
    suspend fun onPendingTurnStateChanged(state: MemoryPendingTurnState)
}

object NoOpMemoryPendingTurnObserver : MemoryPendingTurnObserver {
    override suspend fun onPendingTurnStateChanged(state: MemoryPendingTurnState) = Unit
}
