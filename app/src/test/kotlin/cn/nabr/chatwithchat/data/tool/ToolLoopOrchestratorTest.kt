package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.dto.ApiState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) { prompt ->
            assertTrue(prompt.contains("Available tools:"))
            Result.success("""{"type":"final_answer","content":"No tool needed."}""")
        }

        assertTrue(result is ToolLoopResult.FinalAnswer)
        assertEquals("No tool needed.", (result as ToolLoopResult.FinalAnswer).content)
        assertTrue(executedCalls.isEmpty())
    }

    @Test
    fun `provider owned progress label is used for test tool`() = runBlocking {
        val progress = mutableListOf<ApiState>()
        val provider = object : ToolProvider {
            override val definition: ToolDefinition = ToolDefinition(
                name = "test_tool",
                description = "A test tool.",
                parameters = ToolDefinition.Parameters()
            )
            override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

            override fun progressLabel(call: ToolCall): String = "label:${call.id}"

            override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
                ToolResult(callId = call.id, name = call.name, content = "done")
        }
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = ToolExecutor(ToolRegistry(listOf(provider))),
            config = ToolLoopConfig(maxToolRounds = 1)
        )

        val result = orchestrator.runLoop(
            tools = orchestrator.toolDefinitions,
            onProgress = { state -> progress += state }
        ) {
            Result.success("""{"type":"tool_calls","tool_calls":[{"id":"call_provider","name":"test_tool","arguments":{}}]}""")
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        assertEquals(
            listOf(
                ApiState.ToolStarted("test_tool", "label:call_provider"),
                ApiState.ToolFinished("test_tool", "label:call_provider")
            ),
            progress
        )
    }

    @Test
    fun `current datetime tool executes through json fallback loop`() = runBlocking {
        val provider = CurrentDateTimeToolProvider(
            clock = Clock.fixed(Instant.parse("2026-07-02T03:04:05Z"), ZoneOffset.UTC),
            zoneId = ZoneId.of("Asia/Shanghai")
        )
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = ToolExecutor(ToolRegistry(listOf(provider))),
            config = ToolLoopConfig(maxToolRounds = 1)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
            Result.success("""{"type":"tool_calls","tool_calls":[{"id":"call_datetime","name":"current_datetime","arguments":{}}]}""")
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val toolResults = result as ToolLoopResult.ToolResults
        assertEquals(1, toolResults.results.size)
        assertFalse(toolResults.results.single().isError)
        assertTrue(toolResults.results.single().content.contains("2026-07-02T11:04:05+08:00"))
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("current_datetime"))
    }

    @Test
    fun `model tool call path executes web search and produces second request prompt`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = recordingExecutor(executedCalls),
            config = ToolLoopConfig(maxToolRounds = 1)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
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
                ),
                securityPolicies = mapOf(
                    ToolDefinition.WebSearch.name to ToolSecurityPolicy.ReadOnlyPublic
                )
            )
        )
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = executor,
            config = ToolLoopConfig(maxToolRounds = 1)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
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
            config = ToolLoopConfig(maxToolRounds = 1, maxToolCallsPerRound = 1)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
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

    @Test
    fun `round one web search round two fetch url round three final answer produces final request prompt`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val prompts = mutableListOf<String>()
        val responses = mutableListOf(
            """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"source"}}]}""",
            """{"type":"tool_calls","tool_calls":[{"id":"call_2","name":"fetch_url","arguments":{"url":"https://example.com/source"}}]}""",
            """{"type":"final_answer","content":"Answer with sources."}"""
        )
        val orchestrator = ToolLoopOrchestrator(recordingExecutor(executedCalls))

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) { prompt ->
            prompts += prompt
            Result.success(responses.removeAt(0))
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val toolResults = result as ToolLoopResult.ToolResults
        assertEquals(listOf("web_search", "fetch_url"), executedCalls.map { it.name })
        assertTrue(prompts[1].contains("Tool scratchpad:"))
        assertTrue(prompts[1].contains("Result for web_search"))
        assertTrue(prompts[2].contains("Result for fetch_url"))
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("Answer with sources."))
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("Tool results are available"))
    }

    @Test
    fun `infinite tool call behavior stops at max tool rounds`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = recordingExecutor(executedCalls),
            config = ToolLoopConfig(maxToolRounds = 2)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
            Result.success(
                """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"again"}}]}"""
            )
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        assertEquals(2, executedCalls.size)
        assertTrue((result as ToolLoopResult.ToolResults).finalAnswerPrompt.orEmpty().contains("Tool results are available"))
    }

    @Test
    fun `duplicate tool calls in the same round are deduped`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = recordingExecutor(executedCalls),
            config = ToolLoopConfig(maxToolRounds = 1, maxToolCallsPerRound = 4)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
            Result.success(
                """
                {"type":"tool_calls","tool_calls":[
                  {"id":"call_1","name":"web_search","arguments":{"query":"same"}},
                  {"id":"call_2","name":"web_search","arguments":{"query":"same"}},
                  {"id":"call_3","name":"web_search","arguments":{"query":"different"}}
                ]}
                """.trimIndent()
            )
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        assertEquals(listOf("call_1", "call_3"), executedCalls.map { it.id })
    }

    @Test
    fun `search query budget rejects extra calls as recoverable tool errors`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = recordingExecutor(
                executedCalls = executedCalls,
                webSearchPolicy = ToolPolicy(
                    maxCallsPerRequest = 1,
                    maxCallsPerRequestErrorKey = "max_search_queries_per_request"
                )
            ),
            config = ToolLoopConfig(
                maxToolRounds = 1,
                maxToolCallsPerRound = 4
            )
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
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
        val toolResults = result as ToolLoopResult.ToolResults
        assertEquals(listOf("call_1"), executedCalls.map { it.id })
        assertEquals(2, toolResults.results.size)
        assertTrue(toolResults.results.single { it.callId == "call_2" }.isError)
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("tool_budget_exceeded:max_search_queries_per_request"))
    }

    @Test
    fun `custom tool policy rejects extra calls without config field`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val provider = recordingProvider(
            definition = ToolDefinition(
                name = "current_datetime",
                description = "Returns the current date and time.",
                parameters = ToolDefinition.Parameters()
            ),
            executedCalls = executedCalls,
            policy = ToolPolicy(maxCallsPerRequest = 1)
        )
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = ToolExecutor(ToolRegistry(listOf(provider))),
            config = ToolLoopConfig(maxToolRounds = 1, maxToolCallsPerRound = 4)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
            Result.success(
                """
                {"type":"tool_calls","tool_calls":[
                  {"id":"call_1","name":"current_datetime","arguments":{}},
                  {"id":"call_2","name":"current_datetime","arguments":{"timezone":"UTC"}}
                ]}
                """.trimIndent()
            )
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val toolResults = result as ToolLoopResult.ToolResults
        assertEquals(listOf("call_1"), executedCalls.map { it.id })
        assertTrue(toolResults.results.single { it.callId == "call_2" }.isError)
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("tool_budget_exceeded:max_current_datetime_calls_per_request"))
    }

    @Test
    fun `custom tool policy chat budget is enforced across rounds`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val provider = recordingProvider(
            definition = ToolDefinition(
                name = "current_datetime",
                description = "Returns the current date and time.",
                parameters = ToolDefinition.Parameters()
            ),
            executedCalls = executedCalls,
            policy = ToolPolicy(maxCallsPerChat = 1)
        )
        val responses = mutableListOf(
            """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"current_datetime","arguments":{}}]}""",
            """{"type":"tool_calls","tool_calls":[{"id":"call_2","name":"current_datetime","arguments":{"timezone":"UTC"}}]}"""
        )
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = ToolExecutor(ToolRegistry(listOf(provider))),
            config = ToolLoopConfig(maxToolRounds = 2, maxToolCallsPerRound = 4)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
            Result.success(responses.removeAt(0))
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val toolResults = result as ToolLoopResult.ToolResults
        assertEquals(listOf("call_1"), executedCalls.map { it.id })
        assertTrue(toolResults.results.single { it.callId == "call_2" }.isError)
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("tool_budget_exceeded:max_current_datetime_calls_per_chat"))
    }

    @Test
    fun `unavailable tool calls are rejected before executor runs`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = recordingExecutor(executedCalls),
            config = ToolLoopConfig(maxToolRounds = 1, maxToolCallsPerRound = 4)
        )

        val result = orchestrator.runLoop(
            tools = listOf(ToolDefinition.FetchUrl)
        ) {
            Result.success(
                """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"news"}}]}"""
            )
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val toolResults = result as ToolLoopResult.ToolResults
        assertTrue(executedCalls.isEmpty())
        assertTrue(toolResults.results.single { it.callId == "call_1" }.isError)
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("tool_unavailable:web_search"))
    }

    @Test
    fun `global tool call budget is enforced across rounds`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val responses = mutableListOf(
            """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"one"}}]}""",
            """{"type":"tool_calls","tool_calls":[{"id":"call_2","name":"fetch_url","arguments":{"url":"https://example.com/two"}}]}"""
        )
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = recordingExecutor(executedCalls),
            config = ToolLoopConfig(maxToolRounds = 2, maxToolCallsPerChat = 1)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
            Result.success(responses.removeAt(0))
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val toolResults = result as ToolLoopResult.ToolResults
        assertEquals(listOf("call_1"), executedCalls.map { it.id })
        assertTrue(toolResults.results.single { it.callId == "call_2" }.isError)
        assertTrue(toolResults.finalAnswerPrompt.orEmpty().contains("tool_budget_exceeded:max_tool_calls_per_chat"))
    }

    @Test
    fun `native execution session enforces global call budget across rounds`() = runBlocking {
        val executedCalls = mutableListOf<ToolCall>()
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = recordingExecutor(executedCalls),
            config = ToolLoopConfig(maxToolCallsPerChat = 1)
        )
        val executionSession = orchestrator.createExecutionSession()

        val firstRound = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_1", "web_search", """{"query":"one"}""")),
            tools = orchestrator.toolDefinitions,
            executionSession = executionSession
        )
        val secondRound = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_2", "fetch_url", """{"url":"https://example.com/two"}""")),
            tools = orchestrator.toolDefinitions,
            executionSession = executionSession
        )

        assertFalse(firstRound.single().isError)
        assertEquals(listOf("call_1"), executedCalls.map { call -> call.id })
        assertTrue(secondRound.single().isError)
        assertTrue(secondRound.single().content.contains("tool_budget_exceeded:max_tool_calls_per_chat"))
    }

    @Test
    fun `native execution session preserves later content before sources inside aggregate result budget`() = runBlocking {
        val provider = object : ToolProvider {
            override val definition = ToolDefinition("source_tool", "Returns a sourced result.", ToolDefinition.Parameters())
            override val securityPolicy = ToolSecurityPolicy.ReadOnlyPublic

            override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = ToolResult(
                callId = call.id,
                name = call.name,
                content = "x".repeat(config.maxToolResultChars),
                sources = listOf(
                    ToolSource.PublicUrl(
                        title = "Source ${call.id}",
                        url = "https://example.com/${call.id}"
                    )
                )
            )
        }
        val config = ToolLoopConfig(
            maxToolResultChars = 1_000,
            maxTotalToolResultChars = 1_500
        )
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = ToolExecutor(ToolRegistry(listOf(provider))),
            config = config
        )
        val executionSession = orchestrator.createExecutionSession()

        val firstRound = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_1", provider.definition.name, "{}")),
            tools = orchestrator.toolDefinitions,
            executionSession = executionSession
        )
        val secondRound = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_2", provider.definition.name, "{}")),
            tools = orchestrator.toolDefinitions,
            executionSession = executionSession
        )
        val results = firstRound + secondRound

        assertTrue(orchestrator.sourceMetadata(secondRound).isEmpty())
        assertTrue(secondRound.single().content.isNotEmpty())
        assertTrue(secondRound.single().content.length < firstRound.single().content.length)
        assertTrue(results.sumOf { result -> result.payloadCharCount() } <= config.maxTotalToolResultChars)
    }

    @Test
    fun `final answer prompt can cite urls from tool results`() = runBlocking {
        val executor = ToolExecutor(
            ToolRegistry(
                definitions = listOf(ToolDefinition.WebSearch),
                handlers = mapOf(
                    ToolDefinition.WebSearch.name to ToolHandler { call, _ ->
                        ToolResult(
                            callId = call.id,
                            name = call.name,
                            content = "Title: Example\nURL: https://example.com/source\nSnippet: useful source"
                        )
                    }
                ),
                securityPolicies = mapOf(
                    ToolDefinition.WebSearch.name to ToolSecurityPolicy.ReadOnlyPublic
                )
            )
        )
        val orchestrator = ToolLoopOrchestrator(
            toolExecutor = executor,
            config = ToolLoopConfig(maxToolRounds = 1)
        )

        val result = orchestrator.runLoop(tools = orchestrator.toolDefinitions) {
            Result.success(
                """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"source"}}]}"""
            )
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val prompt = (result as ToolLoopResult.ToolResults).finalAnswerPrompt.orEmpty()
        assertTrue(prompt.contains("cite the source URLs"))
        assertTrue(prompt.contains("https://example.com/source"))
    }

    private fun recordingExecutor(
        executedCalls: MutableList<ToolCall>,
        webSearchPolicy: ToolPolicy = ToolPolicy.default(),
        fetchUrlPolicy: ToolPolicy = ToolPolicy.default()
    ): ToolExecutor = ToolExecutor(
        ToolRegistry(
            listOf(
                recordingProvider(ToolDefinition.WebSearch, executedCalls, webSearchPolicy),
                recordingProvider(ToolDefinition.FetchUrl, executedCalls, fetchUrlPolicy)
            )
        )
    )

    private fun recordingProvider(
        definition: ToolDefinition,
        executedCalls: MutableList<ToolCall>,
        policy: ToolPolicy = ToolPolicy.default()
    ): ToolProvider = object : ToolProvider {
        override val definition: ToolDefinition = definition
        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic
        override val policy: ToolPolicy = policy

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
            executedCalls += call
            return ToolResult(
                callId = call.id,
                name = call.name,
                content = "Result for ${call.name}"
            )
        }
    }
}
