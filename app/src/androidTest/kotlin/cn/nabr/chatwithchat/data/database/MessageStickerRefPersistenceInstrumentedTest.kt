package cn.nabr.chatwithchat.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.isEffectivelyBlank
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageStickerRefPersistenceInstrumentedTest {
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
    fun nonEmptyStickerRef_survivesRoomCloseAndReopen() = runBlocking {
        val chatId = database.chatRoomDao().addChatRoom(
            ChatRoomV2(
                title = "Sticker persistence",
                enabledPlatform = listOf("provider-1")
            )
        ).toInt()
        val stickerRef = MessageStickerRef(
            instanceId = "tool-call-1",
            stickerId = "builtin.reactions.heart_burst",
            assetKey = "dba9e56ea591340374e3edecf341661beeabd48a0a8834aa3dbb051ddbac7f62",
            altText = "A burst of hearts"
        )
        database.messageDao().addMessages(
            MessageV2(
                chatId = chatId,
                content = "",
                stickerRefs = listOf(stickerRef),
                platformType = "provider-1"
            )
        )

        database.close()
        database = openDatabase()

        val persistedMessage = database.messageDao().loadMessages(chatId).single()
        assertEquals(listOf(stickerRef), persistedMessage.stickerRefs)
        assertFalse(persistedMessage.isEffectivelyBlank())
        assertEquals(1, database.messageDao().countStickerAssetReferences(stickerRef.assetKey))
    }

    private fun openDatabase(): ChatDatabaseV2 = Room.databaseBuilder(
        context,
        ChatDatabaseV2::class.java,
        DATABASE_NAME
    ).build()

    private companion object {
        const val DATABASE_NAME = "message-sticker-ref-persistence-test.db"
    }
}
