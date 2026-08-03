package cn.nabr.chatwithchat.data.history

import androidx.sqlite.db.SupportSQLiteQuery
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryLexicalHit
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryBackfillCheckpointEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryEmbeddingEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexQueueEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryRetrieverTest {
    @Test
    fun `vector-only candidates exclude the current chat`() = kotlinx.coroutines.runBlocking {
        val currentChat = projection(chatId = 7, turnKey = "chat:7:user:1")
        val otherChat = projection(chatId = 8, turnKey = "chat:8:user:2")
        val retriever = ChatHistoryRetriever(
            historyDao = FakeHistoryDao(listOf(currentChat, otherChat)),
            vectorStore = object : HistoryVectorStore {
                override suspend fun publish(projections: List<ChatHistoryTurnProjection>): Result<HistoryVectorPublication> =
                    Result.success(HistoryVectorPublication(1, "hash", "descriptor", projections.size))

                override suspend fun query(query: String, limit: Int): Result<List<HistoryVectorHit>> =
                    Result.success(
                        listOf(
                            HistoryVectorHit(currentChat.turnKey, 0.99f),
                            HistoryVectorHit(otherChat.turnKey, 0.9f)
                        )
                    )
            }
        )

        val snapshot = retriever.retrieve(
            ChatHistoryRetrievalRequest(currentChatId = 7, query = "project context")
        )

        assertEquals(listOf(otherChat.turnKey), snapshot.snippets.map(ChatHistorySnippet::turnKey))
    }

    private fun projection(chatId: Int, turnKey: String) = ChatHistoryProjectionEntity(
        projectionId = chatId.toLong(),
        turnKey = turnKey,
        chatId = chatId,
        userMessageId = chatId,
        assistantMessageId = chatId + 100,
        assistantPlatformUid = "provider",
        title = "Chat $chatId",
        userContent = "project context",
        assistantContent = "answer for $chatId",
        searchTerms = "project context answer",
        contentHash = "hash-$chatId",
        projectionVersion = ChatHistoryContract.PROJECTION_VERSION,
        eligibilityState = ChatHistoryContract.ELIGIBLE,
        createdAt = 1,
        updatedAt = 1
    )
}

private class FakeHistoryDao(
    private val projections: List<ChatHistoryProjectionEntity>
) : ChatHistoryDao {
    override suspend fun getProjection(turnKey: String): ChatHistoryProjectionEntity? = projections.firstOrNull { it.turnKey == turnKey }
    override suspend fun getProjectionsForChat(chatId: Int): List<ChatHistoryProjectionEntity> = projections.filter { it.chatId == chatId }
    override suspend fun getEligibleProjections(): List<ChatHistoryProjectionEntity> = projections
    override suspend fun getProjectionsByTurnKeys(turnKeys: List<String>): List<ChatHistoryProjectionEntity> =
        projections.filter { it.turnKey in turnKeys }
    override suspend fun upsertProjection(projection: ChatHistoryProjectionEntity): Long = projection.projectionId
    override suspend fun updateProjection(projection: ChatHistoryProjectionEntity) = Unit
    override suspend fun deleteProjection(turnKey: String): Int = 0
    override suspend fun enqueue(item: ChatHistoryIndexQueueEntity) = Unit
    override suspend fun getQueueBatch(limit: Int): List<ChatHistoryIndexQueueEntity> = emptyList()
    override suspend fun countQueue(): Int = 0
    override suspend fun acknowledge(turnKey: String) = Unit
    override suspend fun recordQueueAttempt(turnKey: String, requestedAt: Long) = Unit
    override suspend fun upsertBackfillCheckpoint(checkpoint: ChatHistoryBackfillCheckpointEntity) = Unit
    override suspend fun getBackfillCheckpoint(checkpointId: String): ChatHistoryBackfillCheckpointEntity? = null
    override suspend fun upsertIndexState(state: ChatHistoryIndexStateEntity) = Unit
    override suspend fun getIndexState(stateId: String): ChatHistoryIndexStateEntity? = null
    override suspend fun searchLexical(query: SupportSQLiteQuery): List<ChatHistoryLexicalHit> = emptyList()
    override suspend fun upsertEmbeddings(embeddings: List<ChatHistoryEmbeddingEntity>) = Unit
    override suspend fun getEmbeddings(descriptorHash: String): List<ChatHistoryEmbeddingEntity> = emptyList()
    override suspend fun deleteOrphanedEmbeddings(): Int = 0
}
