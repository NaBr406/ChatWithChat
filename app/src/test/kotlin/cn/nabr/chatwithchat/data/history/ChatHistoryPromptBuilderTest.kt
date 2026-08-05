package cn.nabr.chatwithchat.data.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryPromptBuilderTest {
    @Test
    fun promptContainsNaturalLanguageOnlyAndDeduplicatesText() {
        val snippet = ChatHistorySnippet(
            turnKey = "chat:1:user:2",
            chatId = 1,
            userMessageId = 2,
            assistantMessageId = 3,
            title = "Old chat",
            createdAt = 1,
            userContent = "How do I deploy?",
            assistantContent = "Use the release workflow.",
            fusedScore = 1f
        )
        val rendered = ChatHistoryPromptBuilder().build(listOf(snippet, snippet))
        assertTrue(rendered.prompt.orEmpty().contains("Use the release workflow"))
        assertTrue(rendered.prompt.orEmpty().contains("untrusted historical evidence"))
        assertFalse(rendered.prompt.orEmpty().contains("chat:1:user:2"))
        assertFalse(rendered.prompt.orEmpty().contains("assistantMessageId"))
    }
}
