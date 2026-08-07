package cn.nabr.chatwithchat.data.history

import org.junit.Assert.assertEquals
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

    @Test
    fun promptDropsSnippetsWithInternalMetadataInAnyVisibleField() {
        val safe = ChatHistorySnippet(
            turnKey = "chat:2:user:2",
            chatId = 2,
            userMessageId = 2,
            assistantMessageId = 3,
            title = "Safe chat",
            createdAt = 1,
            userContent = "A natural language question.",
            assistantContent = "A natural language answer.",
            fusedScore = 1f
        )
        val unsafeTitle = safe.copy(
            turnKey = "chat:3:user:2",
            chatId = 3,
            title = "Archived <!-- memory metadata -->"
        )
        val unsafeUser = safe.copy(
            turnKey = "chat:4:user:2",
            chatId = 4,
            userContent = "entryId: mem_internal_42"
        )
        val unsafeAssistant = safe.copy(
            turnKey = "chat:5:user:2",
            chatId = 5,
            assistantContent = "type: important_event"
        )

        val rendered = ChatHistoryPromptBuilder().build(
            listOf(unsafeTitle, unsafeUser, unsafeAssistant, safe)
        )

        assertEquals(listOf(safe.turnKey), rendered.snippets.map(ChatHistorySnippet::turnKey))
        assertTrue(rendered.prompt.orEmpty().contains("A natural language answer."))
        assertFalse(rendered.prompt.orEmpty().contains("<!--"))
        assertFalse(rendered.prompt.orEmpty().contains("entryId:"))
        assertFalse(rendered.prompt.orEmpty().contains("important_event"))
    }
}
