package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopOrchestratorTest {

    @Test
    fun `model final answer path does not execute tools`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(recordingExecutor(executedCalls))

        val result = orchestrator.runSingleRound { prompt ->
            assertTrue(prompt.contains("Available tools:"))
            Result.success("""{"type":"final_answer","content":"No tool needed."}""")
        }

        assertTrue(result is ToolLoopResult.FinalAnswer)
        assertEquals("No tool needed.", (result as ToolLoopResult.FinalAnswer).content)
        assertTrue(executedCalls.isEmpty())
    }

    @Test
    fun `model tool call path executes web search and produces second request prompt`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(recordingExecutor(executedCalls))

        val result = orchestrator.runSingleRound {
            Result.success(
                """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"latest Android SDK"}}]}"""
            )
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val toolResults = result as ToolLoopResult.ToolResults
        assertEquals(1, executedCalls.size)
        assertEquals("web_search", executedCalls.first().name)
        assertEquals(1, toolResults.results.size)
        assertFalse(toolResults.results.first().isError)
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("Tool results are available"))
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("Result for web_search"))
    }

    @Test
    fun `tool failure is injected into second request prompt as error result`() = runBlocking {
        val executor = ToolExecutor(
            ToolRegistry(
                definitions = listOf(ToolDefinition.WebSearch),
                handlers = mapOf(
                    ToolDefinition.WebSearch.name to ToolHandler { call, _ ->
                        ToolResult(
                            callId = call.id,
                            name = call.name,
                            content = "search backend unavailable",
                            isError = true
                        )
                    }
                )
            )
        )
        val orchestrator = ToolLoopOrchestrator(executor)

        val result = orchestrator.runSingleRound {
            Result.success(
                """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"news"}}]}"""
            )
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val prompt = (result as ToolLoopResult.ToolResults).finalAnswerPrompt.orEmpty()
        assertTrue(prompt.contains("web_search (call_1) - ERROR"))
        assertTrue(prompt.contains("search backend unavailable"))
    }

    @Test
    fun `more than max allowed tool calls are clipped`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = recordingExecutor(executedCalls),
            config = ToolLoopConfig(maxToolCallsPerRound = 1)
        )

        val result = orchestrator.runSingleRound {
            Result.success(
                """
                {"type":"tool_calls","tool_calls":[
                  {"id":"call_1","name":"web_search","arguments":{"query":"one"}},
                  {"id":"call_2","name":"web_search","arguments":{"query":"two"}}
                ]}
                """.trimIndent()
            )
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        assertEquals(1, executedCalls.size)
        assertEquals(listOf("call_1"), (result as ToolLoopResult.ToolResults).calls.map { it.id })
    }

    private fun recordingExecutor(executedCalls: MutableList<ToolCall>): ToolExecutor = ToolExecutor(
        ToolRegistry(
            definitions = listOf(ToolDefinition.WebSearch),
            handlers = mapOf(
                ToolDefinition.WebSearch.name to ToolHandler { call, _ ->
                    executedCalls += call
                    ToolResult(
                        callId = call.id,
                        name = call.name,
                        content = "Result for ${call.name}"
                    )
                }
            )
        )
    )
}
