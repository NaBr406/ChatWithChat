package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.AnthropicToolChoice
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.tool.CurrentDateTimeToolProvider
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicNativeToolAdapterTest {
    private val adapter = AnthropicNativeToolAdapter()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `anthropic request serializes with tools and tool choice`() {
        val request = MessageRequest(
            model = "claude-sonnet",
            messages = listOf(
                InputMessage(
                    role = MessageRole.USER,
                    content = listOf(TextContent("What changed today?"))
                )
            ),
            maxTokens = 4096,
            stream = true,
            tools = adapter.toAnthropicTools(listOf(ToolDefinition.WebSearch)),
            toolChoice = AnthropicToolChoice.Auto
        )

        val payload = json.encodeToString(request)

        assertTrue(payload.contains(""""tools""""))
        assertTrue(payload.contains(""""name":"web_search""""))
        assertTrue(payload.contains(""""input_schema""""))
        assertTrue(payload.contains(""""additionalProperties":false"""))
        assertTrue(payload.contains(""""tool_choice":{"type":"auto"}"""))
    }

    @Test
    fun `anthropic tool serialization supports current datetime tool`() {
        val payload = json.encodeToString(
            adapter.toAnthropicTools(listOf(CurrentDateTimeToolProvider().definition))
        )

        assertTrue(payload.contains(""""name":"current_datetime""""))
        assertTrue(payload.contains(""""input_schema":{"type":"object","properties":{},"required":[],"additionalProperties":false}"""))
    }

    @Test
    fun `streamed tool use deltas parse to internal tool call`() {
        val start = json.decodeFromString<MessageResponseChunk>(
            """
            {
              "type": "content_block_start",
              "index": 0,
              "content_block": {
                "type": "tool_use",
                "id": "toolu_1",
                "name": "web_search",
                "input": {}
              }
            }
            """.trimIndent()
        )
        val firstDelta = json.decodeFromString<MessageResponseChunk>(
            """
            {
              "type": "content_block_delta",
              "index": 0,
              "delta": {
                "type": "input_json_delta",
                "partial_json": "{\"query\":\"latest"
              }
            }
            """.trimIndent()
        )
        val secondDelta = json.decodeFromString<MessageResponseChunk>(
            """
            {
              "type": "content_block_delta",
              "index": 0,
              "delta": {
                "type": "input_json_delta",
                "partial_json": " Android target SDK\"}"
              }
            }
            """.trimIndent()
        )

        val calls = adapter.toolCallsFromChunks(listOf(start, firstDelta, secondDelta))

        assertEquals(1, calls.size)
        assertEquals("toolu_1", calls.single().id)
        assertEquals("web_search", calls.single().name)
        assertEquals("""{"query":"latest Android target SDK"}""", calls.single().arguments)
    }

    @Test
    fun `continuation messages serialize tool use and tool result`() {
        val messages = adapter.continuationMessages(
            calls = listOf(
                ToolCall(
                    id = "toolu_1",
                    name = "web_search",
                    arguments = """{"query":"news"}"""
                )
            ),
            results = listOf(
                ToolResult(
                    callId = "toolu_1",
                    name = "web_search",
                    content = "Search result",
                    metadata = mapOf("source_0_url" to "https://example.com/source")
                )
            )
        )

        val payload = json.encodeToString(messages)

        assertTrue(payload.contains(""""role":"assistant""""))
        assertTrue(payload.contains(""""type":"tool_use""""))
        assertTrue(payload.contains(""""id":"toolu_1""""))
        assertTrue(payload.contains(""""role":"user""""))
        assertTrue(payload.contains(""""type":"tool_result""""))
        assertTrue(payload.contains(""""tool_use_id":"toolu_1""""))
        assertTrue(payload.contains("Search result"))
        assertTrue(payload.contains("https://example.com/source"))
    }
}
