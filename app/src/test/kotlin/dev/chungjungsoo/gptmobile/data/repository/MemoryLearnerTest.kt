package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLearnerTest {

    @Test
    fun `explicit boundary statement creates boundary memory`() {
        val memories = MemoryRepositoryImpl.learnMemoriesFromText(
            text = "以后别太说教",
            chatId = 1,
            createdAt = 100L
        )

        assertTrue(memories.any { it.type == MemoryType.BOUNDARY || it.type == MemoryType.COMMUNICATION_STYLE })
        assertTrue(memories.any { it.content.contains("说教") })
    }

    @Test
    fun `important event statement creates event memory`() {
        val memories = MemoryRepositoryImpl.learnMemoriesFromText(
            text = "我最近在准备面试，明天还要考试",
            chatId = 1,
            createdAt = 100L
        )

        assertTrue(memories.any { it.type == MemoryType.IMPORTANT_EVENT || it.type == MemoryType.LIFE_CONTEXT })
    }

    @Test
    fun `sensitive statement is skipped by MVP learner`() {
        val memories = MemoryRepositoryImpl.learnMemoriesFromText(
            text = "记住我的银行卡密码是 123456",
            chatId = 1,
            createdAt = 100L
        )

        assertEquals(emptyList<Any>(), memories)
    }
}
