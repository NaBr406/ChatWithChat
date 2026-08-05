package cn.nabr.chatwithchat.data.history

import cn.nabr.chatwithchat.data.database.entity.AssistantRevision
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryProjectionBuilderTest {
    private val builder = ChatHistoryProjectionBuilder { 99L }
    private val room = ChatRoomV2(id = 7, title = "Project", enabledPlatform = listOf("preferred", "other"))

    @Test
    fun selectsPreferredSuccessfulAssistantAndEffectiveRevision() {
        val result = builder.build(
            room,
            listOf(
                MessageV2(id = 10, chatId = 7, content = "What is the plan?", platformType = null, createdAt = 1),
                MessageV2(id = 11, chatId = 7, content = "other answer", platformType = "other", createdAt = 2),
                MessageV2(
                    id = 12,
                    chatId = 7,
                    content = "old answer",
                    revisions = listOf(AssistantRevision("preferred answer", createdAt = 3)),
                    activeRevisionIndex = 0,
                    platformType = "preferred",
                    createdAt = 3
                )
            ),
            preferredPlatformUid = "preferred"
        ).projection

        assertNotNull(result)
        assertEquals(12, result?.assistantMessageId)
        assertEquals("preferred answer", result?.assistantContent)
        assertEquals("chat:7:user:10", result?.turnKey)
        assertEquals(99L, result?.updatedAt)
    }

    @Test
    fun rejectsBlankUserAndErrorOnlyAssistant() {
        val blank = builder.build(
            room,
            listOf(MessageV2(id = 1, chatId = 7, content = " ", platformType = null))
        )
        assertNull(blank.projection)
        assertEquals("blank_user", blank.reason)

        val error = builder.build(
            room,
            listOf(
                MessageV2(id = 2, chatId = 7, content = "question", platformType = null),
                MessageV2(id = 3, chatId = 7, content = "Error: provider failed", platformType = "other")
            )
        )
        assertNull(error.projection)
        assertEquals("no_successful_assistant", error.reason)
    }

    @Test
    fun unchangedHashIsStableAndUsesUtf8Framing() {
        val messages = listOf(
            MessageV2(id = 4, chatId = 7, content = "你好", platformType = null),
            MessageV2(id = 5, chatId = 7, content = "世界", platformType = "other")
        )
        val first = builder.build(room, messages).projection
        val second = builder.build(room, messages).projection
        assertEquals(first?.contentHash, second?.contentHash)
        assertTrue(first?.contentHash?.matches(Regex("[0-9a-f]{64}")) == true)
    }
}
