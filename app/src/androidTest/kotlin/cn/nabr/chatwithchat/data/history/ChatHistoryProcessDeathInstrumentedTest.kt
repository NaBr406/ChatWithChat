package cn.nabr.chatwithchat.data.history

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2Migrations
import cn.nabr.chatwithchat.data.database.ChatHistoryDatabaseCallback
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChatHistoryProcessDeathInstrumentedTest {
    @Test
    fun phase1_persistQueuedWorkAndKillProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetHarnessState(context)
        val database = openDatabase(context)
        database.chatRoomDao().addChatRoom(
            ChatRoomV2(id = 701, title = "History process death", enabledPlatform = listOf("provider"))
        )
        database.messageDao().addMessages(
            MessageV2(
                id = 7_010,
                chatId = 701,
                content = "durable history process death question",
                platformType = null,
                createdAt = 1
            ),
            MessageV2(
                id = 7_011,
                chatId = 701,
                content = "durable history process death answer",
                platformType = "provider",
                createdAt = 2
            )
        )
        val coordinator = coordinator(database)
        coordinator.enqueueChatReconciliation(701)
        assertEquals(1, database.chatHistoryDao().queueCount())
        database.close()
        writePhaseOneMarker(context)

        Process.killProcess(Process.myPid())
        Thread.sleep(PROCESS_DEATH_TIMEOUT_MILLIS)
        fail("Process survived the history process-death failpoint")
    }

    @Test
    fun phase2_reopenAndDrainQueuedWorkAfterProcessDeath() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Phase-one marker is missing", phaseOneMarker(context).isFile)
        val database = openDatabase(context)
        try {
            val coordinator = coordinator(database)
            val result = coordinator.processWork()

            assertEquals(1, result.processed)
            assertEquals(0, database.chatHistoryDao().queueCount())
            assertNotNull(database.chatHistoryDao().findProjection("chat:701:user:7010"))
        } finally {
            database.close()
            resetHarnessState(context)
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

    private fun openDatabase(context: Context): ChatDatabaseV2 = Room.databaseBuilder(
        context,
        ChatDatabaseV2::class.java,
        DATABASE_NAME
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

    private fun resetHarnessState(context: Context) {
        context.deleteDatabase(DATABASE_NAME)
        harnessRoot(context).deleteRecursively()
    }

    private fun writePhaseOneMarker(context: Context) {
        val marker = phaseOneMarker(context)
        marker.parentFile?.mkdirs()
        FileOutputStream(marker).use { output ->
            output.write("ready\n".toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun phaseOneMarker(context: Context): File = File(harnessRoot(context), PHASE_ONE_MARKER_NAME)

    private fun harnessRoot(context: Context): File = File(context.filesDir, HARNESS_ROOT_NAME)

    private companion object {
        const val DATABASE_NAME = "history_process_death_harness"
        const val HARNESS_ROOT_NAME = "history-process-death-harness"
        const val PHASE_ONE_MARKER_NAME = "phase-one-ready"
        const val PROCESS_DEATH_TIMEOUT_MILLIS = 5_000L
    }
}
