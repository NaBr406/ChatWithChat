package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.MemoryChatCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.util.isAssistantErrorMessage
import cn.nabr.chatwithchat.util.stripAssistantErrorNote
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryTurnBatchCoordinator(
    private val turnBatchDao: MemoryTurnBatchDao,
    private val pendingTurnObserver: MemoryPendingTurnObserver = NoOpMemoryPendingTurnObserver,
    private val json: Json = Json { encodeDefaults = true }
) {
    suspend fun recordUserActivity(chatId: Int, activityAt: Long) {
        if (chatId <= 0) return

        val checkpoint = turnBatchDao.getCheckpoint(chatId)
        val effectiveActivityAt = maxOf(checkpoint?.lastUserActivityAt ?: 0L, activityAt)
        val hasPendingTurns = turnBatchDao.countUnclaimedTurns(chatId) > 0
        turnBatchDao.upsertCheckpoint(
            checkpoint?.copy(
                lastUserActivityAt = effectiveActivityAt,
                idleDueAt = (effectiveActivityAt + IDLE_DELAY_SECONDS).takeIf { hasPendingTurns },
                updatedAt = maxOf(checkpoint.updatedAt, activityAt)
            ) ?: MemoryChatCheckpoint(
                chatId = chatId,
                lastUserActivityAt = effectiveActivityAt,
                updatedAt = activityAt
            )
        )
    }

    suspend fun recordCompletedTurn(input: MemoryCompletedTurnInput): MemoryTurnRecordingResult {
        val snapshot = input.toSnapshotOrNull() ?: return MemoryTurnRecordingResult.skipped("no_successful_assistant")
        val payloadJson = json.encodeToString(snapshot)
        val existingTurn = turnBatchDao.getPendingTurn(snapshot.chatId, snapshot.userMessageId)
        if (existingTurn?.claimedJobId != null) {
            return MemoryTurnRecordingResult(
                recorded = true,
                turnKey = existingTurn.turnKey,
                pendingCount = turnBatchDao.countUnclaimedTurns(snapshot.chatId),
                reason = "already_claimed"
            )
        }
        val now = input.completedAt
        turnBatchDao.upsertPendingTurn(
            MemoryPendingTurn(
                turnKey = snapshot.turnKey,
                chatId = snapshot.chatId,
                userMessageId = snapshot.userMessageId,
                payloadJson = payloadJson,
                contentHash = sha256(payloadJson),
                completedAt = snapshot.completedAt,
                claimedJobId = null,
                createdAt = existingTurn?.createdAt ?: now,
                updatedAt = now
            )
        )

        val currentCheckpoint = turnBatchDao.getCheckpoint(snapshot.chatId)
        val activityAt = maxOf(currentCheckpoint?.lastUserActivityAt ?: 0L, input.userMessage.createdAt)
        val pendingSince = listOfNotNull(currentCheckpoint?.pendingSince, snapshot.completedAt).minOrNull()
        val checkpoint = currentCheckpoint?.copy(
            lastObservedUserMessageId = maxOf(currentCheckpoint.lastObservedUserMessageId, snapshot.userMessageId),
            pendingSince = pendingSince,
            lastUserActivityAt = activityAt,
            idleDueAt = activityAt + IDLE_DELAY_SECONDS,
            updatedAt = maxOf(currentCheckpoint.updatedAt, now)
        ) ?: MemoryChatCheckpoint(
            chatId = snapshot.chatId,
            lastObservedUserMessageId = snapshot.userMessageId,
            pendingSince = snapshot.completedAt,
            lastUserActivityAt = activityAt,
            idleDueAt = activityAt + IDLE_DELAY_SECONDS,
            updatedAt = now
        )
        turnBatchDao.upsertCheckpoint(checkpoint)

        val pendingCount = turnBatchDao.countUnclaimedTurns(snapshot.chatId)
        pendingTurnObserver.onPendingTurnStateChanged(
            MemoryPendingTurnState(
                chatId = snapshot.chatId,
                pendingCount = pendingCount,
                idleDueAt = checkpoint.idleDueAt,
                thresholdEligible = pendingCount >= MAX_BATCH_TURNS
            )
        )
        return MemoryTurnRecordingResult(
            recorded = true,
            turnKey = snapshot.turnKey,
            pendingCount = pendingCount
        )
    }

    private fun MemoryCompletedTurnInput.toSnapshotOrNull(): MemoryCompletedTurnSnapshot? {
        if (chatRoom.id <= 0 || userMessage.id <= 0 || userMessage.chatId != chatRoom.id) return null
        val successfulAssistants = assistantMessages.mapNotNull { assistant ->
            val content = stripAssistantErrorNote(assistant.effectiveContent()).trim()
            if (content.isBlank() || isAssistantErrorMessage(content)) return@mapNotNull null
            assistant.platformType?.let { platformUid -> assistant to platformUid }
        }
        val canonicalAssistant = successfulAssistants.firstOrNull { (_, platformUid) -> platformUid == preferredPlatformUid }
            ?: stablePlatformOrder.firstNotNullOfOrNull { platformUid ->
                successfulAssistants.firstOrNull { (_, candidateUid) -> candidateUid == platformUid }
            }
            ?: successfulAssistants.minByOrNull { (_, platformUid) -> platformUid }
            ?: return null
        val assistant = canonicalAssistant.first
        val assistantPlatformUid = canonicalAssistant.second

        return MemoryCompletedTurnSnapshot(
            turnKey = "chat:${chatRoom.id}:user:${userMessage.id}",
            chatId = chatRoom.id,
            chatTitle = chatRoom.title.trim().take(MAX_CHAT_TITLE_CHARS),
            userMessageId = userMessage.id,
            userContent = userMessage.content.trim().take(MAX_MESSAGE_CHARS),
            userAttachments = userMessage.attachments.map { attachment ->
                MemoryAttachmentSnapshot(
                    displayName = attachment.resolvedDisplayName.take(MAX_ATTACHMENT_NAME_CHARS),
                    mimeType = attachment.mimeType.take(MAX_MIME_TYPE_CHARS),
                    sizeBytes = attachment.sizeBytes
                )
            },
            assistantPlatformUid = assistantPlatformUid,
            assistantContent = stripAssistantErrorNote(assistant.effectiveContent()).trim().take(MAX_MESSAGE_CHARS),
            completedAt = completedAt
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val MAX_BATCH_TURNS = 5
        const val IDLE_DELAY_SECONDS = 30L * 60L
        private const val MAX_CHAT_TITLE_CHARS = 200
        private const val MAX_MESSAGE_CHARS = 12_000
        private const val MAX_ATTACHMENT_NAME_CHARS = 240
        private const val MAX_MIME_TYPE_CHARS = 120
    }
}
