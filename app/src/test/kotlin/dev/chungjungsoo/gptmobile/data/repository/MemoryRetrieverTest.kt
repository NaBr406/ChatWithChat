package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatClassification
import dev.chungjungsoo.gptmobile.data.database.entity.ChatMode
import dev.chungjungsoo.gptmobile.data.database.entity.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.database.entity.MemorySource
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrieverTest {

    @Test
    fun `emotional support recalls style and important event but not productivity preference`() {
        val classification = ChatClassification(
            chatId = 1,
            mode = ChatMode.EMOTIONAL_SUPPORT,
            memoryNeeds = listOf(
                MemoryType.COMMUNICATION_STYLE,
                MemoryType.IMPORTANT_EVENT,
                MemoryType.BOUNDARY
            )
        )
        val latestMessage = MessageV2(content = "我今天有点撑不住了", platformType = null)

        val styleScore = MemoryRepositoryImpl.scoreMemory(
            memory(type = MemoryType.COMMUNICATION_STYLE, content = "用户偏好中文自然交流。"),
            classification,
            latestMessage
        )
        val eventScore = MemoryRepositoryImpl.scoreMemory(
            memory(type = MemoryType.IMPORTANT_EVENT, content = "用户最近在准备重要考试。", tags = listOf("考试")),
            classification,
            latestMessage
        )
        val productivityScore = MemoryRepositoryImpl.scoreMemory(
            memory(type = MemoryType.LIGHT_PRODUCTIVITY_PREFERENCE, content = "用户喜欢先看 Kotlin diff。"),
            classification,
            latestMessage
        )

        assertTrue(styleScore >= 2.5f)
        assertTrue(eventScore >= 2.5f)
        assertTrue(productivityScore < 2.5f)
    }

    @Test
    fun `casual chat does not force heavy important event recall`() {
        val classification = ChatClassification(
            chatId = 1,
            mode = ChatMode.CASUAL_CHAT,
            memoryNeeds = listOf(MemoryType.COMMUNICATION_STYLE, MemoryType.STABLE_PROFILE)
        )
        val score = MemoryRepositoryImpl.scoreMemory(
            memory(
                type = MemoryType.IMPORTANT_EVENT,
                content = "用户最近在准备重要考试。",
                tags = listOf("考试")
            ),
            classification,
            MessageV2(content = "随便聊聊，讲点轻松的", platformType = null)
        )

        assertTrue(score < 2.5f)
    }

    @Test
    fun `old high importance communication style can still be recalled`() {
        val classification = ChatClassification(
            chatId = 1,
            mode = ChatMode.CASUAL_CHAT,
            memoryNeeds = listOf(MemoryType.COMMUNICATION_STYLE)
        )
        val oneYearAgo = 1_700_000_000L
        val score = MemoryRepositoryImpl.scoreMemory(
            memory(
                type = MemoryType.COMMUNICATION_STYLE,
                content = "用户偏好中文自然交流。",
                importance = 1f,
                confidence = 1f,
                createdAt = oneYearAgo,
                updatedAt = oneYearAgo
            ),
            classification,
            MessageV2(content = "随便聊聊", platformType = null)
        )

        assertTrue(score >= 2.5f)
    }

    @Test
    fun `resolved important event is not recalled as current pressure source`() {
        val classification = ChatClassification(
            chatId = 1,
            mode = ChatMode.EMOTIONAL_SUPPORT,
            memoryNeeds = listOf(MemoryType.IMPORTANT_EVENT)
        )
        val score = MemoryRepositoryImpl.scoreMemory(
            memory(
                type = MemoryType.IMPORTANT_EVENT,
                content = "用户最近在准备考试。",
                status = MemoryStatus.RESOLVED
            ),
            classification,
            MessageV2(content = "我压力很大", platformType = null)
        )

        assertTrue(score < 2.5f)
    }

    @Test
    fun `sensitive memory does not enter unrelated prompt`() {
        val classification = ChatClassification(
            chatId = 1,
            mode = ChatMode.CASUAL_CHAT,
            memoryNeeds = listOf(MemoryType.STABLE_PROFILE)
        )
        val score = MemoryRepositoryImpl.scoreMemory(
            memory(
                type = MemoryType.STABLE_PROFILE,
                content = "用户有一条敏感健康信息。",
                sensitivity = MemorySensitivity.SENSITIVE,
                source = MemorySource.INFERRED,
                tags = listOf("健康")
            ),
            classification,
            MessageV2(content = "聊点轻松的", platformType = null)
        )

        assertFalse(score >= 2.5f)
    }

    private fun memory(
        type: String,
        content: String,
        tags: List<String> = emptyList(),
        importance: Float = 0.8f,
        confidence: Float = 0.8f,
        status: String = MemoryStatus.ACTIVE,
        sensitivity: String = MemorySensitivity.NORMAL,
        source: String = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt: Long = 1_700_000_000L,
        updatedAt: Long = 1_700_000_000L
    ) = PersonalMemory(
        content = content,
        type = type,
        tags = tags,
        importance = importance,
        confidence = confidence,
        status = status,
        sensitivity = sensitivity,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
