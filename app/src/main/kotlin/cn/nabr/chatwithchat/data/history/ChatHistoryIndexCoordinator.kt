package cn.nabr.chatwithchat.data.history

import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomV2Dao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryBackfillCheckpointEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexQueueEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Inject

class ChatHistoryIndexCoordinator @Inject constructor(
    private val historyDao: ChatHistoryDao,
    private val chatRoomDao: ChatRoomV2Dao,
    private val messageDao: MessageV2Dao,
    private val settingRepository: SettingRepository,
    private val workEnqueuer: ChatHistoryWorkEnqueuer
) {
    suspend fun enqueueChatReconciliation(chatId: Int) {
        if (!settingRepository.fetchMemoryEnabled() || chatId <= 0) return
        val chat = chatRoomDao.getChatRoom(chatId) ?: return
        val now = System.currentTimeMillis() / 1000
        val users = messageDao.loadMessages(chatId).filter { message -> message.platformType == null }
        val currentKeys = users.mapTo(mutableSetOf()) { user -> turnKey(chatId, user.id) }
        users.forEach { user ->
            historyDao.enqueue(
                ChatHistoryIndexQueueEntity(
                    turnKey = turnKey(chatId, user.id),
                    chatId = chatId,
                    userMessageId = user.id,
                    operationHint = ChatHistoryContract.OPERATION_RECONCILE,
                    requestedAt = now
                )
            )
        }
        historyDao.getProjectionsForChat(chatId)
            .filterNot { projection -> projection.turnKey in currentKeys }
            .forEach { projection ->
                historyDao.enqueue(
                    ChatHistoryIndexQueueEntity(
                        turnKey = projection.turnKey,
                        chatId = chatId,
                        userMessageId = projection.userMessageId,
                        operationHint = ChatHistoryContract.OPERATION_RECONCILE,
                        requestedAt = now
                    )
                )
            }
        workEnqueuer.enqueue()
    }

    suspend fun onMemoryEnabledChanged(enabled: Boolean) {
        if (!enabled) return
        val now = System.currentTimeMillis() / 1000
        historyDao.upsertBackfillCheckpoint(
            ChatHistoryBackfillCheckpointEntity(
                checkpointId = ChatHistoryContract.BACKFILL_ID,
                projectionVersion = ChatHistoryContract.PROJECTION_VERSION,
                status = ChatHistoryContract.BACKFILL_RUNNING,
                updatedAt = now
            )
        )
        historyDao.upsertIndexState(
            ChatHistoryIndexStateEntity(
                stateId = ChatHistoryContract.INDEX_STATE_ID,
                projectionGeneration = historyDao.getIndexState(ChatHistoryContract.INDEX_STATE_ID)?.projectionGeneration ?: 0,
                vectorStatus = ChatHistoryContract.VECTOR_UNAVAILABLE,
                updatedAt = now
            )
        )
        workEnqueuer.enqueue()
    }

    suspend fun ensureReconciliationScheduled() {
        if (!settingRepository.fetchMemoryEnabled()) return
        var checkpoint = historyDao.getBackfillCheckpoint(ChatHistoryContract.BACKFILL_ID)
        if (checkpoint == null) {
            checkpoint = ChatHistoryBackfillCheckpointEntity(
                checkpointId = ChatHistoryContract.BACKFILL_ID,
                projectionVersion = ChatHistoryContract.PROJECTION_VERSION,
                status = ChatHistoryContract.BACKFILL_RUNNING,
                updatedAt = System.currentTimeMillis() / 1000
            )
            historyDao.upsertBackfillCheckpoint(checkpoint)
        }
        if (checkpoint.status != ChatHistoryContract.BACKFILL_IDLE || historyDao.countQueue() > 0) {
            workEnqueuer.enqueue()
        }
    }

    private fun turnKey(chatId: Int, userMessageId: Int): String = "chat:$chatId:user:$userMessageId"
}
