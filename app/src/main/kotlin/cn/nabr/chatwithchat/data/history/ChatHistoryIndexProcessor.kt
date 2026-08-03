package cn.nabr.chatwithchat.data.history

import androidx.room.withTransaction
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomV2Dao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryBackfillCheckpointEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Inject

class ChatHistoryIndexProcessor @Inject constructor(
    private val database: ChatDatabaseV2,
    private val historyDao: ChatHistoryDao,
    private val chatRoomDao: ChatRoomV2Dao,
    private val messageDao: MessageV2Dao,
    private val settingRepository: SettingRepository,
    private val projectionBuilder: ChatHistoryProjectionBuilder,
    private val vectorStore: HistoryVectorStore?
) {
    suspend fun process(limit: Int = 32): Boolean {
        if (!settingRepository.fetchMemoryEnabled()) return false
        val queue = historyDao.getQueueBatch(limit)
        if (queue.isNotEmpty()) {
            queue.forEach { item ->
                historyDao.recordQueueAttempt(item.turnKey, now())
                processQueueItem(item.turnKey, item.chatId, item.userMessageId)
            }
            if (settingRepository.fetchMemoryEnabled()) publishVectors()
            return true
        }
        return backfillPage()
    }

    private suspend fun processQueueItem(turnKey: String, chatId: Int, userMessageId: Int) {
        if (!settingRepository.fetchMemoryEnabled()) return
        database.withTransaction {
            val chat = chatRoomDao.getChatRoom(chatId)
            val messages = chat?.let { messageDao.loadMessages(chatId) }.orEmpty()
            val user = messages.firstOrNull { message -> message.id == userMessageId && message.platformType == null }
            val result = if (chat == null || user == null) {
                ChatHistoryProjectionBuildResult(skipCode = "source_missing")
            } else {
                projectionBuilder.build(
                    chatRoom = chat,
                    userMessage = user,
                    assistantMessages = messages.filter { message -> message.linkedMessageId == userMessageId },
                    stablePlatformOrder = chat.enabledPlatform
                )
            }
            val existing = historyDao.getProjection(turnKey)
            val projection = result.projection
            when {
                projection == null -> if (existing != null) historyDao.deleteProjection(turnKey)
                existing == null -> historyDao.upsertProjection(projection.toEntity())
                existing.contentHash != projection.contentHash || existing.eligibilityState != ChatHistoryContract.ELIGIBLE ->
                    historyDao.updateProjection(projection.toEntity(existing.projectionId, existing.createdAt))
            }
            historyDao.acknowledge(turnKey)
            if (existing?.contentHash != projection?.contentHash) advanceGeneration()
        }
    }

    private suspend fun backfillPage(): Boolean {
        if (!settingRepository.fetchMemoryEnabled()) return false
        val checkpoint = historyDao.getBackfillCheckpoint(ChatHistoryContract.BACKFILL_ID)
            ?: return false
        if (checkpoint.status != ChatHistoryContract.BACKFILL_RUNNING) return false
        val page = messageDao.loadHistoryBackfillPage(
            lastChatId = checkpoint.lastChatId ?: 0,
            lastUserMessageId = checkpoint.lastUserMessageId ?: 0,
            limit = BACKFILL_PAGE_SIZE
        )
        if (page.isEmpty()) {
            if (!settingRepository.fetchMemoryEnabled()) return false
            historyDao.upsertBackfillCheckpoint(checkpoint.copy(status = ChatHistoryContract.BACKFILL_IDLE, updatedAt = now()))
            if (settingRepository.fetchMemoryEnabled()) publishVectors()
            return false
        }
        val requestedAt = now()
        if (!settingRepository.fetchMemoryEnabled()) return false
        database.withTransaction {
            page.forEach { message ->
                historyDao.enqueue(
                    cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexQueueEntity(
                        turnKey = "chat:${message.chatId}:user:${message.id}",
                        chatId = message.chatId,
                        userMessageId = message.id,
                        operationHint = ChatHistoryContract.OPERATION_RECONCILE,
                        requestedAt = requestedAt
                    )
                )
            }
            val last = page.last()
            historyDao.upsertBackfillCheckpoint(
                checkpoint.copy(
                    lastChatId = last.chatId,
                    lastUserMessageId = last.id,
                    updatedAt = requestedAt
                )
            )
        }
        return true
    }

    private suspend fun publishVectors() {
        if (!settingRepository.fetchMemoryEnabled()) return
        val store = vectorStore ?: return
        val projections = historyDao.getEligibleProjections().map { projection -> projection.toModel() }
        val publication = store.publish(projections)
        val current = historyDao.getIndexState(ChatHistoryContract.INDEX_STATE_ID)
        val now = now()
        historyDao.upsertIndexState(
            ChatHistoryIndexStateEntity(
                stateId = ChatHistoryContract.INDEX_STATE_ID,
                projectionGeneration = current?.projectionGeneration ?: projections.size.toLong(),
                projectionHash = publication.getOrNull()?.projectionHash,
                vectorPublishedGeneration = publication.getOrNull()?.generation,
                vectorStatus = if (publication.isSuccess) ChatHistoryContract.VECTOR_READY else ChatHistoryContract.VECTOR_UNAVAILABLE,
                updatedAt = now
            )
        )
    }

    private suspend fun advanceGeneration() {
        val current = historyDao.getIndexState(ChatHistoryContract.INDEX_STATE_ID)
        historyDao.upsertIndexState(
            ChatHistoryIndexStateEntity(
                stateId = ChatHistoryContract.INDEX_STATE_ID,
                projectionGeneration = (current?.projectionGeneration ?: 0) + 1,
                projectionHash = current?.projectionHash,
                vectorPublishedGeneration = current?.vectorPublishedGeneration,
                vectorStatus = ChatHistoryContract.VECTOR_STALE,
                updatedAt = now()
            )
        )
    }

    private fun ChatHistoryTurnProjection.toEntity(
        projectionId: Long = 0,
        createdAtOverride: Long = createdAt
    ): ChatHistoryProjectionEntity = ChatHistoryProjectionEntity(
        projectionId = projectionId,
        turnKey = turnKey,
        chatId = chatId,
        userMessageId = userMessageId,
        assistantMessageId = assistantMessageId,
        assistantPlatformUid = assistantPlatformUid,
        title = title,
        userContent = userContent,
        assistantContent = assistantContent,
        searchTerms = searchTerms,
        contentHash = contentHash,
        projectionVersion = projectionVersion,
        eligibilityState = ChatHistoryContract.ELIGIBLE,
        createdAt = createdAtOverride,
        updatedAt = updatedAt
    )

    private fun ChatHistoryProjectionEntity.toModel(): ChatHistoryTurnProjection = ChatHistoryTurnProjection(
        turnKey = turnKey,
        chatId = chatId,
        userMessageId = userMessageId,
        assistantMessageId = assistantMessageId,
        assistantPlatformUid = assistantPlatformUid,
        title = title,
        userContent = userContent,
        assistantContent = assistantContent,
        searchTerms = searchTerms,
        contentHash = contentHash,
        projectionVersion = projectionVersion,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun now(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val BACKFILL_PAGE_SIZE = 64
    }
}
