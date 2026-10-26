package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.presentation.ui.chat.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamTerminalOutcomeTest {
    @Test
    fun explicitDoneErrorMissingDoneAndStopProduceDistinctOutcomes() = runBlocking {
        val cases = listOf(
            listOf(ApiState.Success("answer"), ApiState.Done) to StreamTerminalOutcome.Success,
            listOf(ApiState.Success("partial"), ApiState.Error("failed"), ApiState.Done) to StreamTerminalOutcome.Failed,
            listOf(ApiState.Success("partial")) to StreamTerminalOutcome.Interrupted
        )
        for ((states, expected) in cases) {
            val outcomes = mutableListOf<StreamTerminalOutcome>()
            flowOf(*states.toTypedArray()).handleStates(
                messageFlow = messages(),
                turnIndex = 0,
                platformIdx = 0,
                onLoadingComplete = {},
                onTerminalOutcome = outcomes::add
            )
            assertEquals(listOf(expected), outcomes)
        }
        for (stopped in listOf(false, true)) {
            val outcomes = mutableListOf<StreamTerminalOutcome>()
            try {
                flow {
                    emit(ApiState.Success("partial"))
                    throw CancellationException("cancel")
                }.handleStates(
                    messageFlow = messages(),
                    turnIndex = 0,
                    platformIdx = 0,
                    onLoadingComplete = {},
                    wasStoppedByUser = { stopped },
                    onTerminalOutcome = outcomes::add
                )
            } catch (_: CancellationException) {
                // A stop still propagates cancellation after recording its explicit outcome.
            }
            assertEquals(listOf(if (stopped) StreamTerminalOutcome.Cancelled else StreamTerminalOutcome.Interrupted), outcomes)
        }
    }

    private fun messages() = MutableStateFlow(
        ChatViewModel.GroupedMessages(
            userMessages = listOf(MessageV2(content = "question", platformType = null)),
            assistantMessages = listOf(listOf(MessageV2(content = "", platformType = "platform")))
        )
    )
}
