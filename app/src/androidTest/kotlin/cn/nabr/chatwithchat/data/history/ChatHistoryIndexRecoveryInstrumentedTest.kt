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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryIndexRecoveryInstrumentedTest {
    @Test
    fun durableQueueSurvivesDatabaseReopenAndWorkerDrainsIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "history_recovery_${System.nanoTime()}"
        var database: ChatDatabaseV2? = null
        try {
            database = openDatabase(context, databaseName)
            database.chatRoomDao().addChatRoom(
                ChatRoomV2(id = 46, title = "Durable history", enabledPlatform = listOf("provider"))
            )
            database.messageDao().addMessages(
                MessageV2(id = 600, chatId = 46, content = "durable source", platformType = null, createdAt = 1),
                MessageV2(id = 601, chatId = 46, content = "durable answer", platformType = "provider", createdAt = 2)
            )
            val firstCoordinator = coordinator(database)
            firstCoordinator.enqueueChatReconciliation(46)
            assertEquals(1, database.chatHistoryDao().queueCount())
            database.close()
            database = openDatabase(context, databaseName)

            val secondCoordinator = coordinator(database)
            val result = secondCoordinator.processWork()

            assertEquals(1, result.processed)
            assertEquals(0, database.chatHistoryDao().queueCount())
            assertNotNull(database.chatHistoryDao().findProjection("chat:46:user:600"))
        } finally {
            database?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun coordinator(database: ChatDatabaseV2): ChatHistoryIndexCoordinator = ChatHistoryIndexCoordinator(
        database = database,
        historyDao = database.chatHistoryDao(),
        chatRoomDao = database.chatRoomDao(),
        messageDao = database.messageDao(),
        settingRepository = FakeSettingRepository(true),
        scheduler = object : ChatHistoryWorkScheduler {
            override fun enqueue() = Unit
        },
        projectionBuilder = ChatHistoryProjectionBuilder { 10L },
        vectorStore = NoOpHistoryVectorStore()
    )

    private fun openDatabase(context: Context, name: String): ChatDatabaseV2 = Room.databaseBuilder(
        context,
        ChatDatabaseV2::class.java,
        name
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
}
