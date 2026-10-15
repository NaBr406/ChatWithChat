package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.presentation.ui.chat.ChatViewModel
import cn.nabr.chatwithchat.util.handleStates
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNetworkReconnectTest {
    @Test
    fun `disconnect retries five times and then propagates failure`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val states = mutableListOf<ApiState>()
        val failure = IOException("disconnected")
        try {
            flow<ApiState> {
                withChatNetworkReconnect(waitBeforeRetry = { delays += it }) {
                    attempts++
                    throw failure
                }
            }.toList(states)
            throw AssertionError("Expected network failure")
        } catch (error: IOException) {
            assertTrue(error === failure)
        }
        assertEquals(6, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), delays)
        assertEquals(5, states.filterIsInstance<ApiState.StreamRollback>().size)
    }

    @Test
    fun `reconnect replaces incomplete round while preserving completed tool round`() = runBlocking {
        val messages = MutableStateFlow(
            ChatViewModel.GroupedMessages(
                userMessages = listOf(MessageV2(content = "hello", platformType = null)),
                assistantMessages = listOf(listOf(MessageV2(content = "", platformType = "provider")))
            )
        )
        var attempts = 0
        var toolExecutions = 0
        flow {
            emit(ApiState.Success("Previous round"))
            toolExecutions++
            emit(ApiState.ToolFinished("test", "test"))
            withChatNetworkReconnect(waitBeforeRetry = {}) {
                attempts++
                emit(ApiState.Thinking(if (attempts == 1) "partial thought" else "final thought"))
                emit(ApiState.Success(if (attempts == 1) "partial answer" else "Final answer"))
                if (attempts == 1) throw IOException("disconnected")
            }
            emit(ApiState.Done)
        }.handleStates(messages, 0, 0, onLoadingComplete = {})
        val answer = messages.value.assistantMessages.single().single()
        assertEquals("Previous round\n\nFinal answer", answer.content)
        assertEquals("final thought", answer.thoughts)
        assertEquals(2, attempts)
        assertEquals(1, toolExecutions)
    }

    @Test
    fun `cancellation and non network errors never reconnect`() = runBlocking {
        for (failure in listOf(CancellationException("stop"), IllegalArgumentException("invalid request"))) {
            var attempts = 0
            try {
                flow<ApiState> {
                    withChatNetworkReconnect(waitBeforeRetry = { throw AssertionError("Unexpected retry") }) {
                        attempts++
                        throw failure
                    }
                }.toList()
                throw AssertionError("Expected failure")
            } catch (error: Exception) {
                assertTrue(error === failure)
            }
            assertEquals(1, attempts)
        }
    }
}
