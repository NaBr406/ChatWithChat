package cn.nabr.chatwithchat.data.tool

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionToolExecutorTest {
    @Test
    fun `foreground tool is refused in a headless execution session`() = runBlocking {
        val broker = AskUserQuestionBroker()
        val executor = executor(broker)
        val call = call()

        val result = executor.execute(
            call = call,
            activeToolNames = setOf(call.name),
            sessionState = ToolExecutionSessionState(supportsUserInteraction = false)
        )

        assertTrue(result.isError)
        assertEquals("tool_user_interaction_unavailable", result.metadata["error_code"])
        assertTrue(broker.queued.value.isEmpty())
    }

    @Test
    fun `foreground tool waits without the ordinary tool timeout`() = runBlocking {
        val broker = AskUserQuestionBroker()
        val executor = executor(broker)
        val call = call()
        val pendingResult = async {
            executor.execute(
                call = call,
                activeToolNames = setOf(call.name),
                config = ToolLoopConfig(toolTimeoutSeconds = 0),
                sessionState = ToolExecutionSessionState(supportsUserInteraction = true)
            )
        }
        val pending = withTimeout(1_000) {
            broker.queued
                .first { questions -> questions.any { it.callId == call.id } }
                .first { question -> question.callId == call.id }
        }

        assertTrue(
            broker.submit(
                pending.requestId,
                listOf(
                    AskUserQuestionAnswer(
                        header = "范围",
                        selectedOptions = listOf("只改界面")
                    )
                )
            )
        )

        val result = pendingResult.await()
        assertFalse(result.isError)
        assertEquals("answered", result.metadata["status"])
    }

    private fun executor(broker: AskUserQuestionBroker): ToolExecutor = ToolExecutor(
        toolRegistry = ToolRegistry(listOf(AskUserQuestionToolProvider(broker)))
    )

    private fun call(): ToolCall = ToolCall(
        id = "ask-executor-call",
        name = ToolDefinition.AskUserQuestion.name,
        arguments = """
            {
              "questions": [
                {
                  "header": "范围",
                  "question": "这次需要修改哪些部分？",
                  "options": [
                    {"label": "只改界面", "description": "只调整 Compose UI"},
                    {"label": "界面和数据层", "description": "同时修改 UI、ViewModel 和数据层"}
                  ]
                }
              ]
            }
        """.trimIndent()
    )
}
