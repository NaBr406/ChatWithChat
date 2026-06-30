package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMarkdownExportTest {

    @Test
    fun `markdown export groups memories by type`() {
        val markdown = MemoryMarkdownCodec().encode(
            listOf(
                testMemory(1, "用户偏好自然中文交流。", type = "communication_style"),
                testMemory(2, "用户近期有重要考试。", type = "important_event")
            )
        )

        assertTrue(markdown.contains("# 用户记忆"))
        assertTrue(markdown.contains("## 沟通风格"))
        assertTrue(markdown.contains("- 用户偏好自然中文交流。 状态：生效中"))
        assertTrue(markdown.contains("## 重要事件"))
    }
}
