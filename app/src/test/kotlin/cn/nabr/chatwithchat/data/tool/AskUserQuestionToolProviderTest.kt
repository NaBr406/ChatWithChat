package cn.nabr.chatwithchat.data.tool

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionToolProviderTest {
    @Test
    fun `provider returns selected answers as structured result`() = runBlocking {
        val broker = AskUserQuestionBroker()
        val provider = AskUserQuestionToolProvider(broker)
        val call = call()
        val pendingResult = async { provider.execute(call, ToolLoopConfig.Default) }
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
                        selectedOptions = listOf("界面和数据层")
                    )
                )
            )
        )

        val result = pendingResult.await()
        assertFalse(result.isError)
        assertEquals("answered", result.metadata["status"])
        assertEquals("answered", result.structuredContent?.jsonObject?.get("status")?.jsonPrimitive?.content)
        assertTrue(result.content.contains("界面和数据层"))
    }

    @Test
    fun `provider returns cancellation to the model`() = runBlocking {
        val broker = AskUserQuestionBroker()
        val provider = AskUserQuestionToolProvider(broker)
        val call = call()
        val pendingResult = async { provider.execute(call, ToolLoopConfig.Default) }
        val pending = withTimeout(1_000) {
            broker.queued
                .first { questions -> questions.any { it.callId == call.id } }
                .first { question -> question.callId == call.id }
        }

        assertTrue(broker.cancel(pending.requestId))

        val result = pendingResult.await()
        assertFalse(result.isError)
        assertEquals("cancelled", result.metadata["status"])
        assertTrue(result.content.contains("user_dismissed"))
    }

    @Test
    fun `provider rejects invalid question count`() = runBlocking {
        val provider = AskUserQuestionToolProvider(AskUserQuestionBroker())
        val question = """{"header":"Q","question":"Question?","options":[{"label":"A","description":"A"},{"label":"B","description":"B"}]}"""
        val invalidArguments = (1..5).joinToString(
            separator = ",",
            prefix = """{"questions":[""",
            postfix = "]}"
        ) { question }
        val tooManyQuestions = ToolCall(
            id = "invalid-count",
            name = ToolDefinition.AskUserQuestion.name,
            arguments = invalidArguments
        )

        val result = provider.execute(tooManyQuestions, ToolLoopConfig.Default)

        assertTrue(result.isError)
        assertEquals("tool_arguments_invalid", result.metadata["error_code"])
    }

    private fun call(): ToolCall = ToolCall(
        id = "ask-call",
        name = ToolDefinition.AskUserQuestion.name,
        arguments = validArguments()
    )

    private fun validArguments(): String = """
        {
          "questions": [
            {
              "header": "范围",
              "question": "这次需要修改哪些部分？",
              "multiSelect": false,
              "options": [
                {
                  "label": "只改界面",
                  "description": "只调整 Compose UI"
                },
                {
                  "label": "界面和数据层",
                  "description": "同时修改 UI、ViewModel 和数据层"
                }
              ]
            }
          ]
        }
    """.trimIndent()
}
