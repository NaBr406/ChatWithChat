package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.tool.JsonToolModelOutput
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolResult
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
        assertTrue(prompt.contains("Return exactly one JSON object"))
        assertTrue(prompt.contains("Enabled tool signatures:"))
        assertTrue(output is JsonToolModelOutput.ToolCalls)
        assertEquals("web_search", (output as JsonToolModelOutput.ToolCalls).calls.single().name)
        assertTrue(finalPrompt.contains("已有针对用户最新请求的工具结果"))
        assertTrue(finalPrompt.contains("按最合理的默认范围直接回答"))
        assertTrue(finalPrompt.contains("Draft answer"))
        assertTrue(finalPrompt.contains("https://example.com/source"))
    }

    @Test
    fun `sticker final prompt distinguishes search from a successful send`() {
        val adapter = OpenAICompatibleJsonToolAdapter()
        val config = ToolLoopConfig(maxToolResultChars = 500)

        val searchOnlyPrompt = adapter.buildFinalAnswerPrompt(
            results = listOf(
                ToolResult(
                    callId = "call_search",
                    name = ToolDefinition.SearchStickers.name,
                    content = "sticker_id=builtin.reactions.crying_cat"
                )
            ),
            draftFinalAnswer = "Sticker sent.",
            config = config
        ).orEmpty()
        val sentPrompt = adapter.buildFinalAnswerPrompt(
            results = listOf(
                ToolResult(
                    callId = "call_send",
                    name = ToolDefinition.SendSticker.name,
                    content = "Sticker sent successfully."
                )
            ),
            draftFinalAnswer = "Done.",
            config = config
        ).orEmpty()

        assertTrue(searchOnlyPrompt.contains("没有贴图发送成功"))
        assertTrue(searchOnlyPrompt.contains("不要用文字、Markdown、sticker_id 或内部标记声称或模拟发送"))
        assertTrue(sentPrompt.contains("贴图已进入本地渲染队列"))
        assertTrue(sentPrompt.contains("不要提及 sticker_id"))
    }

    @Test
    fun `anthropic json fallback adapter remains available for unsupported paths`() {
        val adapter = AnthropicToolAdapter()

        val prompt = adapter.buildToolPrompt(
            tools = listOf(ToolDefinition.FetchUrl),
            scratchpad = emptyList(),
            config = ToolLoopConfig.Default
        )

        assertEquals("anthropic_json_fallback", adapter.name)
        assertFalse(adapter.supportsNativeToolCalling)
        assertTrue(prompt.contains("Return exactly one JSON object"))
        assertTrue(prompt.contains("fetch_url"))
    }

    @Test
    fun `google json fallback adapter remains available for unsupported paths`() {
        val adapter = GoogleToolAdapter()

        val result = adapter.parseModelOutput("""{"type":"final_answer","content":"No tool needed."}""").getOrThrow()

        assertEquals("google_json_fallback", adapter.name)
        assertFalse(adapter.supportsNativeToolCalling)
        assertTrue(result is JsonToolModelOutput.FinalAnswer)
        assertEquals("No tool needed.", (result as JsonToolModelOutput.FinalAnswer).content)
    }
}
