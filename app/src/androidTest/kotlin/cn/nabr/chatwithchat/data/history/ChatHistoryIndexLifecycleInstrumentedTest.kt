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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryIndexLifecycleInstrumentedTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var settings: FakeSettingRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_lifecycle_${System.nanoTime()}"
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
        settings = FakeSettingRepository(true)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun queueIsIdempotentAndEditedRowsAreFailClosedUntilReconciled() = runBlocking {
        val room = ChatRoomV2(id = 41, title = "History", enabledPlatform = listOf("provider"))
        database.chatRoomDao().addChatRoom(room)
        database.messageDao().addMessages(
            MessageV2(id = 100, chatId = 41, content = "项目排期", platformType = null, createdAt = 1),
            MessageV2(id = 101, chatId = 41, content = "初始答案", platformType = "provider", createdAt = 2)
        )
        val coordinator = coordinator()
        coordinator.enqueueChatReconciliation(41)
        coordinator.enqueueChatReconciliation(41)
        assertEquals(1, database.chatHistoryDao().queueCount())

        coordinator.processWork()
        val first = database.chatHistoryDao().findProjection("chat:41:user:100")
        assertNotNull(first)
        assertEquals(0, database.chatHistoryDao().queueCount())
        val firstGeneration = database.chatHistoryDao().indexState(HISTORY_INDEX_STATE_ID)?.projectionGeneration

        coordinator.enqueueChatReconciliation(41)
        coordinator.processWork()
        assertEquals(
            firstGeneration,
            database.chatHistoryDao().indexState(HISTORY_INDEX_STATE_ID)?.projectionGeneration
        )

        database.messageDao().editMessages(
            MessageV2(id = 101, chatId = 41, content = "更新后的答案", platformType = "provider", createdAt = 2)
        )
        coordinator.enqueueChatReconciliation(41)
        val staleRetriever = ChatHistoryRetriever(database.chatHistoryDao(), settings)
        assertEquals(
            HistoryRecallMode.NONE,
            staleRetriever.retrieve(HistoryRetrievalRequest("项目排期", currentChatId = 99)).snapshot.mode
        )
        coordinator.processWork()
        val updated = database.chatHistoryDao().findProjection("chat:41:user:100")
        assertEquals("更新后的答案", updated?.assistantContent)
        assertTrue((updated?.updatedAt ?: 0L) >= (first?.updatedAt ?: 0L))
    }

    @Test
    fun disablingRetainsDerivedRowsAndBlocksNewQueueWrites() = runBlocking {
        val room = ChatRoomV2(id = 42, title = "History", enabledPlatform = listOf("provider"))
        database.chatRoomDao().addChatRoom(room)
        database.messageDao().addMessages(
            MessageV2(id = 200, chatId = 42, content = "保留投影", platformType = null, createdAt = 1),
            MessageV2(id = 201, chatId = 42, content = "保留答案", platformType = "provider", createdAt = 2)
        )
        val coordinator = coordinator()
        coordinator.enqueueChatReconciliation(42)
        coordinator.processWork()
        assertNotNull(database.chatHistoryDao().findProjection("chat:42:user:200"))

        settings.updateMemoryEnabled(false)
        database.messageDao().editMessages(
            MessageV2(id = 201, chatId = 42, content = "禁用期间的新答案", platformType = "provider", createdAt = 2)
        )
        coordinator.enqueueChatReconciliation(42)
        coordinator.processWork()

        assertEquals(0, database.chatHistoryDao().queueCount())
        assertEquals("保留答案", database.chatHistoryDao().findProjection("chat:42:user:200")?.assistantContent)
        assertEquals(
            HistoryRecallMode.DISABLED,
            ChatHistoryRetriever(database.chatHistoryDao(), settings)
                .retrieve(HistoryRetrievalRequest("保留投影", currentChatId = 99)).snapshot.mode
        )
    }

    @Test
    fun deleteInvalidatesProjectionBeforeSourceDeletion() = runBlocking {
        val room = ChatRoomV2(id = 43, title = "Delete", enabledPlatform = listOf("provider"))
        database.chatRoomDao().addChatRoom(room)
        database.messageDao().addMessages(
            MessageV2(id = 300, chatId = 43, content = "delete source", platformType = null, createdAt = 1),
            MessageV2(id = 301, chatId = 43, content = "delete answer", platformType = "provider", createdAt = 2)
        )
        val coordinator = coordinator()
        coordinator.enqueueChatReconciliation(43)
        coordinator.processWork()
        assertNotNull(database.chatHistoryDao().findProjection("chat:43:user:300"))

        coordinator.invalidateDeletedChat(43)

        assertNull(database.chatHistoryDao().findProjection("chat:43:user:300"))
        assertEquals(0, database.chatHistoryDao().queueCount())
        assertEquals(
            HistoryRecallMode.NONE,
            ChatHistoryRetriever(database.chatHistoryDao(), settings)
                .retrieve(HistoryRetrievalRequest("delete source", currentChatId = 99))
                .snapshot.mode
        )
        database.chatRoomDao().deleteChatRooms(room)
    }

    @Test
    fun reenableReconcilesTurnsCreatedWhileDisabled() = runBlocking {
        settings.updateMemoryEnabled(false)
        val room = ChatRoomV2(id = 44, title = "Reenable", enabledPlatform = listOf("provider"))
        database.chatRoomDao().addChatRoom(room)
        database.messageDao().addMessages(
            MessageV2(id = 400, chatId = 44, content = "created disabled", platformType = null, createdAt = 1),
            MessageV2(id = 401, chatId = 44, content = "answer after enable", platformType = "provider", createdAt = 2)
        )
        val coordinator = coordinator()
        coordinator.enqueueChatReconciliation(44)
        assertEquals(0, database.chatHistoryDao().queueCount())

        settings.updateMemoryEnabled(true)
        coordinator.onMemoryEnabledChanged(true)
        assertEquals(
            HistoryBackfillStatus.RUNNING,
            database.chatHistoryDao().checkpoint(HISTORY_BACKFILL_CHECKPOINT_ID)?.status
        )
        repeat(4) {
            if (!coordinator.processWork().hasMore) return@repeat
        }

        assertNotNull(database.chatHistoryDao().findProjection("chat:44:user:400"))
        assertEquals(
            HistoryBackfillStatus.IDLE,
            database.chatHistoryDao().checkpoint(HISTORY_BACKFILL_CHECKPOINT_ID)?.status
        )
    }

    @Test
    fun disablingFreezesPendingHistoryWorkAndReenableReconcilesIt() = runBlocking {
        val room = ChatRoomV2(id = 49, title = "Switch transition", enabledPlatform = listOf("provider"))
        database.chatRoomDao().addChatRoom(room)
        database.messageDao().addMessages(
            MessageV2(id = 900, chatId = 49, content = "switch transition question", platformType = null, createdAt = 1),
            MessageV2(id = 901, chatId = 49, content = "old switch answer", platformType = "provider", createdAt = 2)
        )
        val coordinator = coordinator()
        coordinator.enqueueChatReconciliation(49)
        coordinator.processWork()
        val initial = checkNotNull(database.chatHistoryDao().findProjection("chat:49:user:900"))
        val initialState = database.chatHistoryDao().indexState(HISTORY_INDEX_STATE_ID)

        database.messageDao().editMessages(
            MessageV2(id = 901, chatId = 49, content = "new answer while pending", platformType = "provider", createdAt = 2)
        )
        coordinator.enqueueChatReconciliation(49)
        val stale = checkNotNull(database.chatHistoryDao().findProjection("chat:49:user:900"))
        assertEquals(HistoryEligibilityState.STALE, stale.eligibilityState)
        assertEquals(1, database.chatHistoryDao().queueCount())

        settings.updateMemoryEnabled(false)
        val disabled = coordinator.processWork()
        assertTrue(disabled.disabled)
        assertEquals(1, database.chatHistoryDao().queueCount())
        assertEquals(stale, database.chatHistoryDao().findProjection("chat:49:user:900"))
        assertEquals(initialState, database.chatHistoryDao().indexState(HISTORY_INDEX_STATE_ID))
        assertEquals(
            HistoryRecallMode.DISABLED,
            ChatHistoryRetriever(database.chatHistoryDao(), settings)
                .retrieve(HistoryRetrievalRequest("switch transition question", currentChatId = 99))
                .snapshot.mode
        )

        settings.updateMemoryEnabled(true)
        coordinator.onMemoryEnabledChanged(true)
        repeat(12) {
            if (!coordinator.processWork().hasMore) return@repeat
        }

        val reconciled = checkNotNull(database.chatHistoryDao().findProjection("chat:49:user:900"))
        assertEquals(HistoryEligibilityState.ELIGIBLE, reconciled.eligibilityState)
        assertEquals("new answer while pending", reconciled.assistantContent)
        assertEquals(0, database.chatHistoryDao().queueCount())
        assertEquals(HistoryBackfillStatus.IDLE, database.chatHistoryDao().checkpoint(HISTORY_BACKFILL_CHECKPOINT_ID)?.status)
        assertTrue(reconciled.contentHash != initial.contentHash)
    }

    @Test
    fun deletingUserSourceRemovesItsDerivedTurnOnReconciliation() = runBlocking {
        val room = ChatRoomV2(id = 45, title = "Delete turn", enabledPlatform = listOf("provider"))
        database.chatRoomDao().addChatRoom(room)
        database.messageDao().addMessages(
            MessageV2(id = 500, chatId = 45, content = "source turn", platformType = null, createdAt = 1),
            MessageV2(id = 501, chatId = 45, content = "source answer", platformType = "provider", createdAt = 2)
        )
        val coordinator = coordinator()
        coordinator.enqueueChatReconciliation(45)
        coordinator.processWork()
        assertNotNull(database.chatHistoryDao().findProjection("chat:45:user:500"))

        database.messageDao().deleteMessages(
            MessageV2(id = 500, chatId = 45, content = "source turn", platformType = null, createdAt = 1)
        )
        coordinator.enqueueChatReconciliation(45)
        coordinator.processWork()

        assertNull(database.chatHistoryDao().findProjection("chat:45:user:500"))
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
