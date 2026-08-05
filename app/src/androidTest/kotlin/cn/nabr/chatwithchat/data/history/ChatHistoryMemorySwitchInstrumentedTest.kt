package cn.nabr.chatwithchat.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2Migrations
import cn.nabr.chatwithchat.data.database.ChatHistoryDatabaseCallback
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MemoryChatCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobStatus
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkEnqueuer
import cn.nabr.chatwithchat.data.memory.MemoryPromptBuilder
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchTriggerReason
import cn.nabr.chatwithchat.data.repository.MemoryRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryMemorySwitchInstrumentedTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var settings: FakeSettingRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_memory_switch_${System.nanoTime()}"
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
    fun oneSharedSwitchTransitionStopsLongTermWorkAndHistoryConsumption() = runBlocking {
        val room = ChatRoomV2(id = 130, title = "Shared switch", enabledPlatform = listOf("provider"))
        database.chatRoomDao().addChatRoom(room)
        database.messageDao().addMessages(
            MessageV2(id = 1_300, chatId = 130, content = "shared switch question", platformType = null, createdAt = 1),
            MessageV2(id = 1_301, chatId = 130, content = "old answer", platformType = "provider", createdAt = 2)
        )
        database.memoryTurnBatchDao().upsertCheckpoint(
            MemoryChatCheckpoint(
                chatId = 130,
                lastObservedUserMessageId = 5,
                updatedAt = 1
            )
        )
        repeat(5) { index ->
            database.memoryTurnBatchDao().upsertPendingTurn(
                MemoryPendingTurn(
                    turnKey = "chat:130:user:${index + 1}",
                    chatId = 130,
                    userMessageId = index + 1,
                    payloadJson = "{}",
                    contentHash = "hash-$index",
                    completedAt = index.toLong() + 1,
                    createdAt = index.toLong() + 1,
                    updatedAt = index.toLong() + 1
                )
            )
        }

        val scheduler = MemoryMaintenanceScheduler(database.memoryMaintenanceJobDao())
        val turnScheduler = MemoryTurnBatchScheduler(
            turnBatchDao = database.memoryTurnBatchDao(),
            maintenanceJobDao = database.memoryMaintenanceJobDao(),
            maintenanceScheduler = scheduler,
            workEnqueuer = NoOpMemoryWorkEnqueuer,
            settingRepository = settings
        )
        val longTermJob = checkNotNull(
            turnScheduler.enqueueBatchForChat(
                chatId = 130,
                triggerReason = MemoryTurnBatchTriggerReason.THRESHOLD,
                requireFullBatch = true
            )
        )

        val historyCoordinator = coordinator()
        historyCoordinator.enqueueChatReconciliation(130)
        historyCoordinator.processWork()
        database.messageDao().editMessages(
            MessageV2(id = 1_301, chatId = 130, content = "new answer while disabled", platformType = "provider", createdAt = 2)
        )
        historyCoordinator.enqueueChatReconciliation(130)
        assertEquals(1, database.chatHistoryDao().queueCount())

        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryTurnBatchScheduler = turnScheduler,
            chatHistoryIndexCoordinator = historyCoordinator
        )
        settings.updateMemoryEnabled(false)
        repository.onMemoryEnabledChanged(false)

        assertEquals(
            MemoryMaintenanceJobStatus.DISMISSED,
            database.memoryMaintenanceJobDao().getById(longTermJob.jobId)?.status
        )
        assertTrue(database.memoryTurnBatchDao().getPendingTurnsForChat(130).isEmpty())
        assertEquals(1, database.chatHistoryDao().queueCount())
        assertTrue(historyCoordinator.processWork().disabled)
        assertEquals(
            HistoryRecallMode.DISABLED,
            ChatHistoryRetriever(database.chatHistoryDao(), settings)
                .retrieve(HistoryRetrievalRequest("shared switch question", currentChatId = 999))
                .snapshot.mode
        )

        settings.updateMemoryEnabled(true)
        repository.onMemoryEnabledChanged(true)
        assertNotNull(database.chatHistoryDao().checkpoint(HISTORY_BACKFILL_CHECKPOINT_ID))
        repeat(12) {
            if (!historyCoordinator.processWork().hasMore) return@repeat
        }

        assertEquals(0, database.chatHistoryDao().queueCount())
        assertEquals(
            "new answer while disabled",
            database.chatHistoryDao().findProjection("chat:130:user:1300")?.assistantContent
        )
        assertEquals(HistoryBackfillStatus.IDLE, database.chatHistoryDao().checkpoint(HISTORY_BACKFILL_CHECKPOINT_ID)?.status)
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

private object NoOpMemoryWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
    override fun enqueueWork(family: String, delaySeconds: Long) = Unit
}
