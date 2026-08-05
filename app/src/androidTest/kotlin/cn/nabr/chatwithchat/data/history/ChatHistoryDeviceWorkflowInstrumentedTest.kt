package cn.nabr.chatwithchat.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2Migrations
import cn.nabr.chatwithchat.data.database.ChatHistoryDatabaseCallback
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.memory.MemoryPromptBuilder
import cn.nabr.chatwithchat.data.repository.MemoryRepositoryImpl
import cn.nabr.chatwithchat.presentation.ui.chat.MemoryTurnSnapshotKey
import cn.nabr.chatwithchat.presentation.ui.chat.TurnMemoryContextCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryDeviceWorkflowInstrumentedTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var settings: FakeSettingRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_device_workflow_${System.nanoTime()}"
        )
            .addMigrations(
                ChatDatabaseV2Migrations.MIGRATION_1_2,
                ChatDatabaseV2Migrations.MIGRATION_2_3,
                ChatDatabaseV2Migrations.MIGRATION_3_4,
                ChatDatabaseV2Migrations.MIGRATION_4_5,
                ChatDatabaseV2Migrations.MIGRATION_5_6,
                ChatDatabaseV2Migrations.MIGRATION_6_7,
                ChatDatabaseV2Migrations.MIGRATION_7_8,
                ChatDatabaseV2Migrations.MIGRATION_8_9,
                ChatDatabaseV2Migrations.MIGRATION_9_10,
                ChatDatabaseV2Migrations.MIGRATION_10_11,
                ChatDatabaseV2Migrations.MIGRATION_11_12,
                ChatDatabaseV2Migrations.MIGRATION_12_13,
                ChatDatabaseV2Migrations.MIGRATION_13_14,
                ChatDatabaseV2Migrations.MIGRATION_14_15,
                ChatDatabaseV2Migrations.MIGRATION_15_16,
                ChatDatabaseV2Migrations.MIGRATION_16_17,
                ChatDatabaseV2Migrations.MIGRATION_17_18,
                ChatDatabaseV2Migrations.MIGRATION_18_19,
                ChatDatabaseV2Migrations.MIGRATION_19_20
            )
            .addCallback(ChatHistoryDatabaseCallback())
            .build()
        settings = FakeSettingRepository(false)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun connectedWorkflowBackfillsRecallsInvalidatesAndReusesSnapshot() = runBlocking {
        val oldRoom = ChatRoomV2(id = 610, title = "Release history", enabledPlatform = listOf("provider"))
        val newRoom = ChatRoomV2(id = 611, title = "New chat", enabledPlatform = listOf("provider"))
        database.chatRoomDao().addChatRoom(oldRoom)
        database.chatRoomDao().addChatRoom(newRoom)
        val oldUser = MessageV2(
            id = 6_100,
            chatId = oldRoom.id,
            content = "release rollback checklist",
            platformType = null,
            createdAt = 1
        )
        val oldAssistant = MessageV2(
            id = 6_101,
            chatId = oldRoom.id,
            content = "The release checklist requires signing evidence and rollback verification.",
            platformType = "provider",
            createdAt = 2
        )
        database.messageDao().addMessages(oldUser, oldAssistant)

        val coordinator = coordinator()
        coordinator.enqueueChatReconciliation(oldRoom.id)
        assertEquals(0, database.chatHistoryDao().queueCount())

        settings.updateMemoryEnabled(true)
        coordinator.onMemoryEnabledChanged(true)
        drain(coordinator)
        assertNotNull(database.chatHistoryDao().findProjection("chat:610:user:6100"))

        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            chatHistoryRetriever = ChatHistoryRetriever(database.chatHistoryDao(), settings),
            chatHistoryIndexCoordinator = coordinator
        )
        val newQuestion = MessageV2(
            id = 6_110,
            chatId = newRoom.id,
            content = "release rollback checklist",
            platformType = null,
            createdAt = 3
        )
        val first = repository.prepareMemoryContext(newRoom, listOf(newQuestion), listOf(emptyList()))
        assertEquals(HistoryRecallMode.LEXICAL, first.historySnapshot.mode)
        assertTrue(first.prompt.orEmpty().contains("signing evidence"))

        val unrelated = repository.prepareMemoryContext(
            newRoom,
            listOf(newQuestion.copy(content = "banana bread recipe")),
            listOf(emptyList())
        )
        assertEquals(HistoryRecallMode.NONE, unrelated.historySnapshot.mode)
        assertTrue(unrelated.historySnapshot.snippets.isEmpty())

        val cache = TurnMemoryContextCache()
        val cacheKey = MemoryTurnSnapshotKey(
            chatId = newRoom.id,
            turnIndex = 0,
            createdAt = newQuestion.createdAt,
            content = newQuestion.content,
            attachmentSemantics = emptyList()
        )
        var retrievalCalls = 0
        val cached = cache.getOrPrepare(cacheKey) {
            retrievalCalls++
            first
        }
        val retry = cache.getOrPrepare(cacheKey) {
            retrievalCalls++
            unrelated
        }
        assertEquals(1, retrievalCalls)
        assertSame(cached, retry)
        assertSame(first.historySnapshot, retry.historySnapshot)

        database.messageDao().editMessages(
            oldAssistant.copy(content = "The updated release note contains only deployment timing.")
        )
        coordinator.enqueueChatReconciliation(oldRoom.id)
        val stale = repository.prepareMemoryContext(newRoom, listOf(newQuestion), listOf(emptyList()))
        assertEquals(HistoryRecallMode.NONE, stale.historySnapshot.mode)
        drain(coordinator)

        val updated = repository.prepareMemoryContext(
            newRoom,
            listOf(newQuestion.copy(content = "updated release note")),
            listOf(emptyList())
        )
        assertEquals(HistoryRecallMode.LEXICAL, updated.historySnapshot.mode)
        assertTrue(updated.prompt.orEmpty().contains("deployment timing"))
        assertFalse(updated.prompt.orEmpty().contains("signing evidence"))

        coordinator.invalidateDeletedChat(oldRoom.id)
        database.chatRoomDao().deleteChatRooms(oldRoom)
        val afterDelete = repository.prepareMemoryContext(newRoom, listOf(newQuestion), listOf(emptyList()))
        assertEquals(HistoryRecallMode.NONE, afterDelete.historySnapshot.mode)
        assertTrue(afterDelete.historySnapshot.snippets.isEmpty())
    }

    private suspend fun drain(coordinator: ChatHistoryIndexCoordinator) {
        repeat(32) {
            if (!coordinator.processWork().hasMore) return
        }
        error("history workflow did not drain")
    }

    private fun coordinator(): ChatHistoryIndexCoordinator = ChatHistoryIndexCoordinator(
        database = database,
        historyDao = database.chatHistoryDao(),
        chatRoomDao = database.chatRoomDao(),
        messageDao = database.messageDao(),
        settingRepository = settings,
        scheduler = object : ChatHistoryWorkScheduler {
            override fun enqueue() = Unit
        },
        projectionBuilder = ChatHistoryProjectionBuilder { 10L },
        vectorStore = NoOpHistoryVectorStore()
    )
}
