package cn.nabr.chatwithchat.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InitialRequestRecoveryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: ChatDatabaseV2

    @Before
    fun createDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE_NAME)
        database = openDatabase()
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun committedMarker_survivesDatabaseReopen() = runBlocking {
        val chatId = addInitialUserTurn(initialRequestId = -42)

        database.close()
        database = openDatabase()

        assertEquals(chatId, database.messageDao().findChatIdByInitialRequestId(-42))
    }

    @Test
    fun markerLookup_ignoresAssistantMessages() = runBlocking {
        val userChatId = addInitialUserTurn(initialRequestId = -43)
        val assistantChatId = database.chatRoomDao().addChatRoom(chatRoom()).toInt()
        database.messageDao().addMessages(
            MessageV2(
                chatId = assistantChatId,
                content = "assistant",
                linkedMessageId = -43,
                platformType = "provider"
            )
        )

        assertEquals(userChatId, database.messageDao().findChatIdByInitialRequestId(-43))
        assertNull(database.messageDao().findChatIdByInitialRequestId(-999))
    }

    @Test
    fun failedInitialPersistence_rollsBackRoomAndMarker() = runBlocking {
        val failure = runCatching {
            database.withTransaction {
                addInitialUserTurn(initialRequestId = -44)
                error("interrupt transaction")
            }
        }

        assertTrue(failure.isFailure)
        assertTrue(database.chatRoomDao().getChatRooms().isEmpty())
        assertNull(database.messageDao().findChatIdByInitialRequestId(-44))
    }

    private fun openDatabase(): ChatDatabaseV2 = Room.databaseBuilder(
        context,
        ChatDatabaseV2::class.java,
        DATABASE_NAME
    ).build()

    private suspend fun addInitialUserTurn(initialRequestId: Int): Int {
        val chatId = database.chatRoomDao().addChatRoom(chatRoom()).toInt()
        database.messageDao().addMessages(
            MessageV2(
                chatId = chatId,
                content = "initial question",
                linkedMessageId = initialRequestId,
                platformType = null
            )
        )
        return chatId
    }

    private fun chatRoom() = ChatRoomV2(
        title = "Initial request",
        enabledPlatform = listOf("provider")
    )

    private companion object {
        const val DATABASE_NAME = "initial-request-recovery-test.db"
    }
}
