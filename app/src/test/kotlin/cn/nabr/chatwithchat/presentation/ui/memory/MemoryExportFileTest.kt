package cn.nabr.chatwithchat.presentation.ui.memory

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryExportFileTest {

    @Test
    fun `memory export file name is timestamped markdown`() {
        val now = LocalDateTime.of(2026, 9, 26, 15, 30, 45)

        assertEquals(
            "ChatWithChat-Memory-20260926-153045.md",
            memoryExportFileName(now)
        )
    }

    @Test
    fun `memory markdown is written as utf8 without changing content`() {
        val markdown = "# 用户记忆\n\n- 偏好直接、可验证的回答\n"
        val output = ByteArrayOutputStream()

        writeMemoryMarkdown(output, markdown)

        assertEquals(markdown, output.toString(Charsets.UTF_8.name()))
    }
}
