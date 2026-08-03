package cn.nabr.chatwithchat.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryPromptBuilderTest {
    @Test
    fun `packs bounded snippets with source chat diversification`() {
        val snippets = (1..5).map { index ->
            ChatHistorySnippet(
                turnKey = "chat:1:user:$index",
                chatId = 1,
                userMessageId = index,
                assistantMessageId = index + 100,
                chatTitle = "same",
                createdAt = index.toLong(),
                text = "same answer $index",
                fusedScore = 1f - index / 100f
            )
        } + ChatHistorySnippet(
            turnKey = "chat:2:user:1",
            chatId = 2,
            userMessageId = 1,
            assistantMessageId = 101,
            chatTitle = "other",
            createdAt = 1,
            text = "other answer",
            fusedScore = 0.5f
        )

        val rendered = ChatHistoryPromptBuilder().build(snippets)

        assertEquals(3, rendered.snippets.size)
        assertEquals(2, rendered.snippets.count { it.chatId == 1 })
        assertTrue(rendered.prompt!!.contains("仅作为参考证据"))
        assertFalse(rendered.prompt.contains("chat:1:user"))
        assertTrue(rendered.estimatedTokens > 0)
    }

    @Test
    fun `prompt does not expose internal paths from a historical snippet`() {
        val rendered = ChatHistoryPromptBuilder().build(
            snippets = listOf(
                ChatHistorySnippet(
                    turnKey = "chat:2:user:1",
                    chatId = 2,
                    userMessageId = 1,
                    assistantMessageId = 2,
                    chatTitle = "old",
                    createdAt = 1,
                    text = "用户提到 MEMORY.md 和 source_path: /private/path",
                    fusedScore = 1f
                )
            )
        )

        assertFalse(rendered.prompt!!.contains("MEMORY.md"))
        assertTrue(rendered.prompt.contains("内部标记"))
    }
}
