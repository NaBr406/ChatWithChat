package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatClassification
import dev.chungjungsoo.gptmobile.data.database.entity.ChatMode
import dev.chungjungsoo.gptmobile.data.database.entity.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.database.entity.MemorySource
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryType
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptBuilderTest {

    @Test
    fun `prompt includes natural use constraint`() {
        val prompt = MemoryRepositoryImpl.buildMemoryPromptByRules(
            memories = listOf(
                PersonalMemory(
                    content = "用户偏好中文自然交流。",
                    type = MemoryType.COMMUNICATION_STYLE
                )
            ),
            classification = ChatClassification(chatId = 1, mode = ChatMode.CASUAL_CHAT)
        )

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("Relevant user memories:"))
        assertTrue(prompt.contains("用户偏好中文自然交流"))
        assertTrue(prompt.contains("do not force mentioning it"))
    }

    @Test
    fun `prompt filters unconfirmed sensitive memories`() {
        val prompt = MemoryRepositoryImpl.buildMemoryPromptByRules(
            memories = listOf(
                PersonalMemory(
                    content = "用户有一条敏感健康信息。",
                    type = MemoryType.STABLE_PROFILE,
                    sensitivity = MemorySensitivity.SENSITIVE,
                    source = MemorySource.INFERRED
                ),
                PersonalMemory(
                    content = "用户不喜欢被过度说教。",
                    type = MemoryType.BOUNDARY
                )
            ),
            classification = ChatClassification(chatId = 1, mode = ChatMode.CASUAL_CHAT)
        )

        assertNotNull(prompt)
        assertFalse(prompt!!.contains("敏感健康信息"))
        assertTrue(prompt.contains("用户不喜欢被过度说教"))
    }
}
