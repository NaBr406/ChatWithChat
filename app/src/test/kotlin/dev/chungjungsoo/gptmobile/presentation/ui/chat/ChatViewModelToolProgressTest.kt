package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelToolProgressTest {
    @Test
    fun `failed tool progress replaces matching running search`() {
        val states = emptyList<ChatViewModel.ToolProgressState>()
            .appendToolProgress(ApiState.ToolStarted("web_search", "current time now"))
            .appendToolProgress(ApiState.ToolFailed("web_search", "timestamp check failed"))

        assertEquals(1, states.size)
        assertEquals("web_search", states.single().toolName)
        assertEquals("current time now", states.single().label)
        assertEquals(ChatViewModel.ToolProgressStatus.Failed, states.single().status)
        assertEquals("timestamp check failed", states.single().message)
    }

    @Test
    fun `finished tool progress replaces matching running fetch`() {
        val states = emptyList<ChatViewModel.ToolProgressState>()
            .appendToolProgress(ApiState.ToolStarted("fetch_url", "https://example.com"))
            .appendToolProgress(ApiState.ToolFinished("fetch_url", "https://example.com"))

        assertEquals(1, states.size)
        assertEquals("fetch_url", states.single().toolName)
        assertEquals("https://example.com", states.single().label)
        assertEquals(ChatViewModel.ToolProgressStatus.Finished, states.single().status)
    }

    @Test
    fun `generic tool progress replaces matching running state`() {
        val states = emptyList<ChatViewModel.ToolProgressState>()
            .appendToolProgress(ApiState.ToolStarted("current_datetime", "current_datetime"))
            .appendToolProgress(ApiState.ToolFinished("current_datetime", "current_datetime"))

        assertEquals(1, states.size)
        assertEquals("current_datetime", states.single().toolName)
        assertEquals("current_datetime", states.single().label)
        assertEquals(ChatViewModel.ToolProgressStatus.Finished, states.single().status)
    }

    @Test
    fun `failed tool progress without matching running state is retained`() {
        val states = emptyList<ChatViewModel.ToolProgressState>()
            .appendToolProgress(ApiState.ToolFailed("web_search", "web_search_backend_not_configured"))

        assertEquals(1, states.size)
        assertEquals("web_search", states.single().toolName)
        assertEquals("web_search", states.single().label)
        assertEquals(ChatViewModel.ToolProgressStatus.Failed, states.single().status)
    }
}
