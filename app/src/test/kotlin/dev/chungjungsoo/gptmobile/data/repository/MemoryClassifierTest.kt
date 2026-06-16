package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryClassifierTest {

    @Test
    fun `emotional support keywords classify current message`() {
        val classification = MemoryRepositoryImpl.classifyChatByRules(
            chatId = 1,
            latestUserMessage = MessageV2(content = "我今天有点撑不住了", platformType = null),
            allUserMessages = emptyList()
        )

        assertEquals(ChatMode.EMOTIONAL_SUPPORT, classification.mode)
        assertTrue(classification.memoryNeeds.contains("communication_style"))
        assertTrue(classification.memoryNeeds.contains("important_event"))
    }

    @Test
    fun `casual chat keywords classify light conversation`() {
        val classification = MemoryRepositoryImpl.classifyChatByRules(
            chatId = 1,
            latestUserMessage = MessageV2(content = "随便聊聊，讲点轻松的", platformType = null),
            allUserMessages = emptyList()
        )

        assertEquals(ChatMode.CASUAL_CHAT, classification.mode)
    }
}
