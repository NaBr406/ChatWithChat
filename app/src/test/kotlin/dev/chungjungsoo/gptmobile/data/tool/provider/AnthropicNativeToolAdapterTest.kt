package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.AnthropicToolChoice
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.tool.CurrentDateTimeToolProvider
import dev.chungjungsoo.gptmobile.data.tool.ToolArgumentsTooLargeException
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolCallIdentityLimitExceededException
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.ToolSource
import dev.chungjungsoo.gptmobile.data.tool.complexSchemaToolDefinition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun `anthropic tools serialize the complete canonical schema`() {
        val schema = adapter.toAnthropicTools(listOf(complexSchemaToolDefinition())).single().inputSchema
        val properties = schema.getValue("properties").jsonObject

        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(
            listOf("mode", "options", "tags", "retries", "endpoint"),
            schema.getValue("required").jsonArray.map { value -> value.jsonPrimitive.content }
        )
        assertEquals(
            listOf("safe", "fast"),
            properties.getValue("mode").jsonObject.getValue("enum").jsonArray.map { value -> value.jsonPrimitive.content }
        )
        assertEquals("object", properties.getValue("options").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(properties.getValue("options").jsonObject.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(
            "string",
            properties.getValue("tags").jsonObject.getValue("items").jsonObject.getValue("type").jsonPrimitive.content
        )
        assertEquals("uri", properties.getValue("endpoint").jsonObject.getValue("format").jsonPrimitive.content)
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
    fun `streamed anthropic arguments stop accumulating at configured limit`() {
        val chunk = json.decodeFromString<MessageResponseChunk>(
            """
            {
              "type": "content_block_delta",
              "index": 0,
              "delta": {"type": "input_json_delta", "partial_json": "12345"}
            }
            """.trimIndent()
        )

        val exception = assertThrows(ToolArgumentsTooLargeException::class.java) {
            adapter.toolCallsFromChunks(listOf(chunk), ToolLoopConfig(maxToolArgumentChars = 4))
        }

        assertEquals("tool_arguments_too_large", exception.message)
    }

    @Test
    fun `anthropic counts tool use blocks without input`() {
        val chunks = (0..1).map { index ->
            json.decodeFromString<MessageResponseChunk>(
                """
                {
                  "type": "content_block_start",
                  "index": $index,
                  "content_block": {
                    "type": "tool_use",
                    "id": "toolu_$index",
                    "name": "current_datetime"
                  }
                }
                """.trimIndent()
            )
        }

        assertThrows(ToolCallIdentityLimitExceededException::class.java) {
            adapter.toolCallsFromChunks(chunks, ToolLoopConfig(maxToolCallsPerRound = 1))
        }
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
                    metadata = mapOf("source_0_url" to "https://example.com/source"),
                    structuredContent = buildJsonObject { put("count", 1) },
                    sources = listOf(ToolSource.PublicUrl("Example", "https://example.com/source"))
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
        assertTrue(payload.contains("structured_content"))
        assertTrue(payload.contains("public_url"))
    }
}
