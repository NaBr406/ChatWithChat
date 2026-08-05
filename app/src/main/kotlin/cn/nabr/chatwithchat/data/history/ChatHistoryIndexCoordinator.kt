package cn.nabr.chatwithchat.data.history

import androidx.room.withTransaction
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomV2Dao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexQueueEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryVectorEntryEntity
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.repository.SettingRepository
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import javax.inject.Inject

class ChatHistoryIndexCoordinator @Inject constructor(
    private val database: ChatDatabaseV2,
    private val historyDao: ChatHistoryDao,
    private val chatRoomDao: ChatRoomV2Dao,
    private val messageDao: MessageV2Dao,
    private val settingRepository: SettingRepository,
    private val scheduler: ChatHistoryWorkScheduler,
    private val projectionBuilder: ChatHistoryProjectionBuilder = ChatHistoryProjectionBuilder(),
    private val vectorStore: HistoryVectorStore = NoOpHistoryVectorStore()
) {
    suspend fun enqueueChatReconciliation(chatId: Int) {
        if (chatId <= 0 || !settingRepository.fetchMemoryEnabled()) return
        val chat = chatRoomDao.getChatRooms().firstOrNull { it.id == chatId } ?: return
        val messages = messageDao.loadMessages(chatId)
        val users = messages.filter { it.platformType == null && it.id > 0 }
        val existingProjections = historyDao.findProjectionsForChat(chat.id)
        val currentUserIds = users.mapTo(mutableSetOf(), MessageV2::id)
        val now = now()
        database.withTransaction {
            historyDao.markChatState(chatId, HistoryEligibilityState.STALE, now)
            users.forEach { user ->
                historyDao.upsertQueue(
                    ChatHistoryIndexQueueEntity(
                        turnKey = "chat:${chat.id}:user:${user.id}",
                        chatId = chat.id,
                        userMessageId = user.id,
                        operationHint = HistoryQueueOperation.RECONCILE,
                        requestedAt = now
                    )
                )
            }
            existingProjections
                .filterNot { projection -> projection.userMessageId in currentUserIds }
                .forEach { projection ->
                    historyDao.upsertQueue(
                        ChatHistoryIndexQueueEntity(
                            turnKey = projection.turnKey,
                            chatId = chat.id,
                            userMessageId = projection.userMessageId,
                            operationHint = HistoryQueueOperation.RECONCILE,
                            requestedAt = now
                        )
                    )
                }
        }
        scheduler.enqueue()
    }

    suspend fun enqueueChats(chatIds: List<Int>) {
        chatIds.distinct().forEach { chatId -> enqueueChatReconciliation(chatId) }
    }

    suspend fun invalidateDeletedChat(chatId: Int) {
        val turnKeys = historyDao.turnKeysForChat(chatId)
        database.withTransaction {
            historyDao.deleteChatProjections(chatId)
            historyDao.deleteChatQueue(chatId)
            if (turnKeys.isNotEmpty()) {
                historyDao.deleteEmbeddings(turnKeys)
                historyDao.deleteVectorEntriesForTurns(turnKeys)
                advanceGeneration("deleted:$chatId:${turnKeys.joinToString(",")}")
            }
        }
    }

    suspend fun onMemoryEnabledChanged(enabled: Boolean) {
        if (!enabled) return
        database.withTransaction {
            historyDao.upsertCheckpoint(
                (historyDao.checkpoint(HISTORY_BACKFILL_CHECKPOINT_ID)
                    ?: defaultCheckpoint()).copy(
                    lastChatId = null,
                    lastUserMessageId = null,
                    projectionVersion = CURRENT_PROJECTION_VERSION,
                    status = HistoryBackfillStatus.RUNNING,
                    updatedAt = now()
                )
            )
            historyDao.allProjections().forEach { projection ->
                if (projection.eligibilityState == HistoryEligibilityState.ELIGIBLE) {
                    historyDao.upsertProjection(
                        projection.copy(
                            eligibilityState = HistoryEligibilityState.STALE,
                            updatedAt = now()
                        )
                    )
                }
            }
        }
        scheduler.enqueue()
    }

    suspend fun ensureReconciliationScheduled() {
        if (!settingRepository.fetchMemoryEnabled()) return
        val checkpoint = historyDao.checkpoint(HISTORY_BACKFILL_CHECKPOINT_ID)
        if (checkpoint?.status != HistoryBackfillStatus.IDLE || historyDao.queueCount() > 0) {
            scheduler.enqueue()
        }
    }

    suspend fun processWork(limit: Int = 24): HistoryProcessResult {
        if (!settingRepository.fetchMemoryEnabled()) return HistoryProcessResult(disabled = true)
        var processed = 0
        val queued = historyDao.nextQueue(limit)
        queued.forEach { queue ->
            if (!settingRepository.fetchMemoryEnabled()) return@forEach
            reconcileQueueRow(queue)
            processed++
        }
        val backfillProcessed = processBackfillPage(limit = 4)
        val queueRemaining = historyDao.queueCount() > 0
        if (!queueRemaining && !backfillProcessed.hasMore) publishVectorSnapshotIfNeeded()
        val remaining = historyDao.queueCount() > 0 || backfillProcessed.hasMore
        return HistoryProcessResult(
            processed = processed + backfillProcessed.processed,
            hasMore = remaining
        )
    }

    private suspend fun reconcileQueueRow(queue: ChatHistoryIndexQueueEntity) {
        val chat = chatRoomDao.getChatRooms().firstOrNull { it.id == queue.chatId }
        val messages = chat?.let { messageDao.loadMessages(it.id) }
        val result = chat?.let { projectionBuilder.buildAll(it, messages.orEmpty()) }
            ?.firstOrNull { it.projection?.turnKey == queue.turnKey }
        database.withTransaction {
            val old = historyDao.findProjection(queue.turnKey)
            val projection = result?.projection
            if (projection == null) {
                historyDao.deleteProjectionByKey(queue.turnKey)
                historyDao.deleteEmbeddings(queue.turnKey)
                historyDao.deleteVectorEntryForTurn(queue.turnKey)
                if (old != null) advanceGeneration("deleted:${queue.turnKey}")
            } else {
                val row = ChatHistoryProjectionEntity(
                    projectionId = old?.projectionId ?: 0,
                    turnKey = projection.turnKey,
                    chatId = projection.chatId,
                    userMessageId = projection.userMessageId,
                    assistantMessageId = projection.assistantMessageId,
                    assistantPlatformUid = projection.assistantPlatformUid,
                    title = projection.title,
                    userContent = projection.userContent,
                    assistantContent = projection.assistantContent,
                    searchTerms = projection.searchTerms,
                    contentHash = projection.contentHash,
                    projectionVersion = projection.projectionVersion,
                    eligibilityState = HistoryEligibilityState.ELIGIBLE,
                    createdAt = old?.createdAt ?: projection.createdAt,
                    updatedAt = if (old?.contentHash == projection.contentHash) old.updatedAt else projection.updatedAt
                )
                historyDao.upsertProjection(row)
                if (old?.contentHash != projection.contentHash) {
                    historyDao.deleteEmbeddings(queue.turnKey)
                    historyDao.deleteVectorEntryForTurn(queue.turnKey)
                }
                if (old == null || old.contentHash != row.contentHash) {
                    advanceGeneration(row.contentHash)
                }
            }
            historyDao.deleteQueue(queue.turnKey)
        }
    }

    private suspend fun processBackfillPage(limit: Int): BackfillResult {
        if (!settingRepository.fetchMemoryEnabled()) return BackfillResult(disabled = true)
        val checkpoint = historyDao.checkpoint(HISTORY_BACKFILL_CHECKPOINT_ID) ?: defaultCheckpoint()
        if (checkpoint.status != HistoryBackfillStatus.RUNNING) return BackfillResult()
        val ids = if (checkpoint.lastChatId == null) {
            historyDao.firstChatIds(limit)
        } else {
            historyDao.chatIdsAfter(checkpoint.lastChatId, limit)
        }
        if (ids.isEmpty()) {
            historyDao.upsertCheckpoint(checkpoint.copy(status = HistoryBackfillStatus.IDLE, updatedAt = now()))
            return BackfillResult(processed = 0, hasMore = false)
        }
        ids.forEach { chatId ->
            if (settingRepository.fetchMemoryEnabled()) {
                enqueueChatReconciliation(chatId)
            }
        }
        if (!settingRepository.fetchMemoryEnabled()) return BackfillResult(disabled = true, hasMore = true)
        val lastChatId = ids.last()
        historyDao.upsertCheckpoint(
            checkpoint.copy(
                lastChatId = lastChatId,
                lastUserMessageId = null,
                projectionVersion = CURRENT_PROJECTION_VERSION,
                status = HistoryBackfillStatus.RUNNING,
                updatedAt = now()
            )
        )
        val hasMore = historyDao.chatIdsAfter(lastChatId, 1).isNotEmpty()
        return BackfillResult(processed = ids.size, hasMore = hasMore)
    }

    private suspend fun advanceGeneration(contentHash: String) {
        val state = historyDao.indexState(HISTORY_INDEX_STATE_ID)
            ?: ChatHistoryIndexStateEntity(
                stateId = HISTORY_INDEX_STATE_ID,
                projectionGeneration = 0,
                vectorStatus = HistoryVectorStatus.MISSING,
                updatedAt = now()
            )
        historyDao.upsertIndexState(
            state.copy(
                projectionGeneration = state.projectionGeneration + 1,
                projectionHash = sha256("${state.projectionHash.orEmpty()}\n$contentHash"),
                vectorStatus = HistoryVectorStatus.STALE,
                updatedAt = now()
            )
        )
    }

    private suspend fun publishVectorSnapshotIfNeeded() {
        val state = historyDao.indexState(HISTORY_INDEX_STATE_ID) ?: return
        if (state.vectorStatus == HistoryVectorStatus.READY &&
            state.vectorPublishedGeneration == state.projectionGeneration
        ) return
        val projections = historyDao.projectionsByState(HistoryEligibilityState.ELIGIBLE)
        val result = vectorStore.publish(projections)
        if (result.isFailure) {
            historyDao.upsertIndexState(
                state.copy(vectorStatus = HistoryVectorStatus.FAILED, updatedAt = now())
            )
        }
    }

    private fun defaultCheckpoint(): cn.nabr.chatwithchat.data.database.entity.ChatHistoryBackfillCheckpointEntity =
        cn.nabr.chatwithchat.data.database.entity.ChatHistoryBackfillCheckpointEntity(
            checkpointId = HISTORY_BACKFILL_CHECKPOINT_ID,
            projectionVersion = CURRENT_PROJECTION_VERSION,
            status = HistoryBackfillStatus.IDLE,
            updatedAt = now()
        )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun now(): Long = System.currentTimeMillis() / 1000
}

data class HistoryProcessResult(
    val processed: Int = 0,
    val hasMore: Boolean = false,
    val disabled: Boolean = false
)

private data class BackfillResult(
    val processed: Int = 0,
    val hasMore: Boolean = false,
    val disabled: Boolean = false
)
