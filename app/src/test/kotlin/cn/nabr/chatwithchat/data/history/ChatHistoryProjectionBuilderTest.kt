package cn.nabr.chatwithchat.data.history

import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryProjectionBuilderTest {
    private val builder = ChatHistoryProjectionBuilder()
    private val chat = ChatRoomV2(
        id = 7,
        title = "旅行计划",
        enabledPlatform = listOf("preferred", "fallback"),
        createdAt = 10,
        updatedAt = 20
    )

    @Test
    fun `selects preferred successful assistant and uses effective content`() {
        val user = MessageV2(id = 10, chatId = 7, content = "帮我规划路线", platformType = null, createdAt = 11)
        val fallback = MessageV2(
            id = 12,
            chatId = 7,
            content = "fallback answer",
            platformType = "fallback",
            linkedMessageId = 10,
            createdAt = 12
        )
        val preferred = MessageV2(
            id = 11,
            chatId = 7,
            content = "old answer",
            platformType = "preferred",
            linkedMessageId = 10,
            revisions = listOf(cn.nabr.chatwithchat.data.database.entity.AssistantRevision("current answer", createdAt = 13)),
            activeRevisionIndex = 0,
            createdAt = 13
        )

        val result = builder.build(chat, user, listOf(fallback, preferred), "preferred")

        val projection = result.projection!!
        assertEquals("chat:7:user:10", projection.turnKey)
        assertEquals(11, projection.assistantMessageId)
        assertEquals("current answer", projection.assistantContent)
        assertTrue(projection.searchTerms.contains("规划"))
        assertEquals(64, projection.contentHash.length)
    }

    @Test
    fun `rejects blank user and missing successful assistant`() {
        val blank = MessageV2(id = 10, chatId = 7, content = " ", platformType = null)
        val user = MessageV2(id = 10, chatId = 7, content = "question", platformType = null)

        assertNull(builder.build(chat, blank, emptyList()).projection)
        assertEquals("blank_user", builder.build(chat, blank, emptyList()).skipCode)
        assertEquals("no_successful_assistant", builder.build(chat, user, emptyList()).skipCode)
    }

    @Test
    fun `hash changes when canonical assistant changes but turn key stays stable`() {
        val user = MessageV2(id = 10, chatId = 7, content = "question", platformType = null)
        val first = MessageV2(id = 11, chatId = 7, content = "one", platformType = "preferred", linkedMessageId = 10)
        val second = first.copy(content = "two")

        val firstProjection = builder.build(chat, user, listOf(first), "preferred").projection!!
        val secondProjection = builder.build(chat, user, listOf(second), "preferred").projection!!

        assertEquals(firstProjection.turnKey, secondProjection.turnKey)
        assertNotEquals(firstProjection.contentHash, secondProjection.contentHash)
    }
}
