package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.presentation.ui.chat.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingPhaseEventsTest {

    private fun messageFlow() = MutableStateFlow(
        ChatViewModel.GroupedMessages(
            userMessages = listOf(MessageV2(content = "Hello", platformType = null)),
            assistantMessages = listOf(listOf(MessageV2(content = "", platformType = "platform-1")))
        )
    )

    private suspend fun kotlinx.coroutines.flow.Flow<ApiState>.thinkingEdges(): List<Boolean> {
        val edges = mutableListOf<Boolean>()
        handleStates(
            messageFlow = messageFlow(),
            turnIndex = 0,
            platformIdx = 0,
            onLoadingComplete = {},
            onThinkingChanged = { edges += it }
        )
        return edges
    }

    @Test
    fun `thinking starts with reasoning and ends at the first visible answer text`() = runBlocking {
        val edges = flowOf(
            ApiState.Loading,
            ApiState.Thinking("Let me check"),
            ApiState.Thinking(" the numbers."),
            ApiState.Success("\n\n"),
            ApiState.Success("The answer is 4."),
            ApiState.Done
        ).thinkingEdges()

        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun `whitespace before the answer does not end thinking early`() = runBlocking {
        val observed = mutableListOf<Pair<String, Boolean>>()
        var current = false
        flow {
            emit(ApiState.Thinking("reasoning"))
            observed += "after-thinking" to current
            emit(ApiState.Success("  "))
            observed += "after-whitespace" to current
            emit(ApiState.Success("Answer"))
            observed += "after-answer" to current
            emit(ApiState.Done)
        }.handleStates(
            messageFlow = messageFlow(),
            turnIndex = 0,
            platformIdx = 0,
            onLoadingComplete = {},
            onThinkingChanged = { current = it }
        )

        assertEquals(
            listOf("after-thinking" to true, "after-whitespace" to true, "after-answer" to false),
            observed
        )
    }

    @Test
    fun `tool calls between reasoning rounds keep one thinking phase`() = runBlocking {
        val edges = flowOf(
            ApiState.Thinking("I should search."),
            ApiState.ToolStarted("web_search", "query"),
            ApiState.ToolFinished("web_search", "query"),
            ApiState.Thinking("The results say"),
            ApiState.ToolStarted("fetch", "page"),
            ApiState.ToolFailed("fetch", "page"),
            ApiState.Thinking("Fall back to the snippets"),
            ApiState.Success("Final answer"),
            ApiState.Done
        ).thinkingEdges()

        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun `text written before a tool call does not end the agent run`() = runBlocking {
        val edges = flowOf(
            ApiState.Thinking("Plan the steps."),
            ApiState.Success("Let me look that up."),
            ApiState.ToolStarted("web_search", "query"),
            ApiState.ToolFinished("web_search", "query"),
            ApiState.Success("Final answer"),
            ApiState.Done
        ).thinkingEdges()

        assertEquals(listOf(true, false, true, false), edges)
    }

    @Test
    fun `a reconnect rollback does not end thinking`() = runBlocking {
        val edges = flowOf(
            ApiState.StreamCheckpoint("r1"),
            ApiState.Thinking("partial"),
            ApiState.StreamRollback("r1"),
            ApiState.Thinking("partial again"),
            ApiState.Success("Answer"),
            ApiState.Done
        ).thinkingEdges()

        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun `tool calls without any reasoning never report thinking`() = runBlocking {
        val edges = flowOf(
            ApiState.ToolStarted("web_search", "query"),
            ApiState.ToolFinished("web_search", "query"),
            ApiState.Success("Answer"),
            ApiState.Done
        ).thinkingEdges()

        assertEquals(emptyList<Boolean>(), edges)
    }

    @Test
    fun `a stream that ends while thinking reports the end`() = runBlocking {
        val edges = flowOf(ApiState.Thinking("still going")).thinkingEdges()

        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun `an error while thinking ends thinking`() = runBlocking {
        val edges = flowOf(ApiState.Thinking("hmm"), ApiState.Error("boom")).thinkingEdges()

        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun `answers without reasoning never report thinking`() = runBlocking {
        val edges = flowOf(ApiState.Success("Hi"), ApiState.Done).thinkingEdges()

        assertEquals(emptyList<Boolean>(), edges)
    }
}
