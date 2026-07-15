package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTurnBatchCoordinatorTest {
    @Test
    fun `four completed turns stay local and the fifth becomes threshold eligible`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        val observer = RecordingPendingTurnObserver()
        val coordinator = MemoryTurnBatchCoordinator(dao, observer)

        (1..4).forEach { messageId ->
            val result = coordinator.recordCompletedTurn(input(userMessageId = messageId))
            assertTrue(result.recorded)
            assertFalse(observer.states.last().thresholdEligible)
        }

        assertEquals(4, dao.countUnclaimedTurns(CHAT_ID))
        val fifth = coordinator.recordCompletedTurn(input(userMessageId = 5))
        assertEquals(5, fifth.pendingCount)
        assertTrue(observer.states.last().thresholdEligible)
        assertEquals(5, observer.states.size)
    }

    @Test
    fun `one failed provider and one successful provider record one canonical answer`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        val coordinator = MemoryTurnBatchCoordinator(dao)
        val result = coordinator.recordCompletedTurn(
            input(
                userMessageId = 1,
                assistantMessages = listOf(
                    assistant("platform-1", "错误：timeout"),
                    assistant("platform-2", "Useful answer")
                ),
                preferredPlatformUid = "platform-1"
            )
        )

        assertTrue(result.recorded)
        val snapshot = Json.decodeFromString<MemoryCompletedTurnSnapshot>(
            dao.getPendingTurnsForChat(CHAT_ID).single().payloadJson
        )
        assertEquals("platform-2", snapshot.assistantPlatformUid)
        assertEquals("Useful answer", snapshot.assistantContent)
    }

    @Test
    fun `preferred successful platform wins over stable fallback order`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        val coordinator = MemoryTurnBatchCoordinator(dao)

        coordinator.recordCompletedTurn(
            input(
                userMessageId = 1,
                assistantMessages = listOf(
                    assistant("platform-1", "First answer"),
                    assistant("platform-2", "Preferred answer")
                ),
                preferredPlatformUid = "platform-2"
            )
        )

        val snapshot = Json.decodeFromString<MemoryCompletedTurnSnapshot>(
            dao.getPendingTurnsForChat(CHAT_ID).single().payloadJson
        )
        assertEquals("platform-2", snapshot.assistantPlatformUid)
        assertEquals("Preferred answer", snapshot.assistantContent)
    }

    @Test
    fun `all providers failing records no completed turn`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        val coordinator = MemoryTurnBatchCoordinator(dao)

        val result = coordinator.recordCompletedTurn(
            input(
                userMessageId = 1,
                assistantMessages = listOf(
                    assistant("platform-1", "Error: timeout"),
                    assistant("platform-2", "")
                )
            )
        )

        assertFalse(result.recorded)
        assertEquals("no_successful_assistant", result.reason)
        assertEquals(0, dao.countUnclaimedTurns(CHAT_ID))
    }

    @Test
    fun `reprocessing one user message updates its snapshot without incrementing count`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        val coordinator = MemoryTurnBatchCoordinator(dao)

        coordinator.recordCompletedTurn(input(userMessageId = 1, assistantMessages = listOf(assistant("platform-1", "First"))))
        coordinator.recordCompletedTurn(input(userMessageId = 1, assistantMessages = listOf(assistant("platform-1", "Updated"))))

        assertEquals(1, dao.countUnclaimedTurns(CHAT_ID))
        val pendingTurn = dao.getPendingTurnsForChat(CHAT_ID).single()
        val snapshot = Json.decodeFromString<MemoryCompletedTurnSnapshot>(pendingTurn.payloadJson)
        assertEquals("Updated", snapshot.assistantContent)
        assertEquals(64, pendingTurn.contentHash.length)
    }

    @Test
    fun `pending snapshot survives coordinator recreation`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        MemoryTurnBatchCoordinator(dao).recordCompletedTurn(input(userMessageId = 1))

        val recreatedCoordinator = MemoryTurnBatchCoordinator(dao)
        recreatedCoordinator.recordCompletedTurn(input(userMessageId = 2))

        assertEquals(listOf(1, 2), dao.getPendingTurnsForChat(CHAT_ID).map { it.userMessageId })
    }

    @Test
    fun `new activity postpones idle deadline without changing pending count`() = runBlocking {
        val dao = InMemoryMemoryTurnBatchDao()
        val coordinator = MemoryTurnBatchCoordinator(dao)
        coordinator.recordCompletedTurn(input(userMessageId = 1, userCreatedAt = 100L, completedAt = 120L))

        coordinator.recordUserActivity(CHAT_ID, activityAt = 500L)

        val checkpoint = dao.getCheckpoint(CHAT_ID)!!
        assertEquals(500L, checkpoint.lastUserActivityAt)
        assertEquals(500L + MemoryTurnBatchCoordinator.IDLE_DELAY_SECONDS, checkpoint.idleDueAt)
        assertEquals(1, dao.countUnclaimedTurns(CHAT_ID))
    }

    private fun input(
        userMessageId: Int,
        assistantMessages: List<MessageV2> = listOf(assistant("platform-1", "Answer $userMessageId")),
        preferredPlatformUid: String? = "platform-1",
        userCreatedAt: Long = userMessageId.toLong(),
        completedAt: Long = userMessageId.toLong() + 10L
    ): MemoryCompletedTurnInput = MemoryCompletedTurnInput(
        chatRoom = ChatRoomV2(
            id = CHAT_ID,
            title = "Test chat",
            enabledPlatform = listOf("platform-1", "platform-2")
        ),
        userMessage = MessageV2(
            id = userMessageId,
            chatId = CHAT_ID,
            content = "Question $userMessageId",
            platformType = null,
            createdAt = userCreatedAt
        ),
        assistantMessages = assistantMessages,
        preferredPlatformUid = preferredPlatformUid,
        stablePlatformOrder = listOf("platform-1", "platform-2"),
        completedAt = completedAt
    )

    companion object {
        private const val CHAT_ID = 7

        private fun assistant(platformUid: String, content: String): MessageV2 = MessageV2(
            id = platformUid.hashCode(),
            chatId = CHAT_ID,
            content = content,
            platformType = platformUid
        )
    }
}

private class RecordingPendingTurnObserver : MemoryPendingTurnObserver {
    val states = mutableListOf<MemoryPendingTurnState>()

    override suspend fun onPendingTurnStateChanged(state: MemoryPendingTurnState) {
        states += state
    }
}
