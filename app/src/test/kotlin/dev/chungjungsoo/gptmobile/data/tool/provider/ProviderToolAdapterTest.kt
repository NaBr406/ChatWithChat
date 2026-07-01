package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.tool.JsonToolModelOutput
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderToolAdapterTest {

    @Test
    fun `openai compatible json adapter renders definitions parses calls and renders results`() {
        val adapter = OpenAICompatibleJsonToolAdapter()

        val definitions = adapter.renderToolDefinitions(listOf(ToolDefinition.WebSearch))
        val prompt = adapter.buildToolPrompt(
            tools = listOf(ToolDefinition.WebSearch),
            scratchpad = emptyList(),
            config = ToolLoopConfig(maxToolRounds = 2, maxToolCallsPerRound = 1)
        )
        val output = adapter.parseModelOutput(
            """{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"news"}}]}"""
        ).getOrThrow()
        val finalPrompt = adapter.buildFinalAnswerPrompt(
            results = listOf(
                ToolResult(
                    callId = "call_1",
                    name = "web_search",
                    content = "URL: https://example.com/source"
                )
            ),
            draftFinalAnswer = "Draft answer",
            config = ToolLoopConfig(maxToolResultChars = 500)
        ).orEmpty()

        assertFalse(adapter.supportsNativeToolCalling)
        assertEquals("openai_compatible_json", adapter.name)
        assertTrue(definitions.contains("Name: web_search"))
        assertTrue(prompt.contains("Return only valid JSON"))
        assertTrue(prompt.contains("Use at most 1 tool calls"))
        assertTrue(output is JsonToolModelOutput.ToolCalls)
        assertEquals("web_search", (output as JsonToolModelOutput.ToolCalls).calls.single().name)
        assertTrue(finalPrompt.contains("Tool results are available"))
        assertTrue(finalPrompt.contains("Draft answer"))
        assertTrue(finalPrompt.contains("https://example.com/source"))
    }

    @Test
    fun `anthropic adapter keeps json fallback until native dto support is verified`() {
        val adapter = AnthropicToolAdapter()

        val prompt = adapter.buildToolPrompt(
            tools = listOf(ToolDefinition.FetchUrl),
            scratchpad = emptyList(),
            config = ToolLoopConfig.Default
        )

        assertEquals("anthropic_json_fallback", adapter.name)
        assertFalse(adapter.supportsNativeToolCalling)
        assertTrue(prompt.contains("Return only valid JSON"))
        assertTrue(prompt.contains("fetch_url"))
    }

    @Test
    fun `google adapter keeps json fallback until native dto support is verified`() {
        val adapter = GoogleToolAdapter()

        val result = adapter.parseModelOutput("""{"type":"final_answer","content":"No tool needed."}""").getOrThrow()

        assertEquals("google_json_fallback", adapter.name)
        assertFalse(adapter.supportsNativeToolCalling)
        assertTrue(result is JsonToolModelOutput.FinalAnswer)
        assertEquals("No tool needed.", (result as JsonToolModelOutput.FinalAnswer).content)
    }
}
