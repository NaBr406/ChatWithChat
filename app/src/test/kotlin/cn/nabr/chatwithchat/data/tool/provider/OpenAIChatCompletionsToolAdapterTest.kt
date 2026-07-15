package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.dto.openai.common.Role
import cn.nabr.chatwithchat.data.dto.openai.common.TextContent
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionToolChoice
import cn.nabr.chatwithchat.data.dto.openai.request.ChatMessage
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.tool.CurrentDateTimeToolProvider
import cn.nabr.chatwithchat.data.tool.ToolArgumentsTooLargeException
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolCallIdentityLimitExceededException
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSource
import cn.nabr.chatwithchat.data.tool.complexSchemaToolDefinition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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

class OpenAIChatCompletionsToolAdapterTest {
    private val adapter = OpenAIChatCompletionsToolAdapter()

    @Test
    fun `chat completion request serializes with tools and tool choice`() {
        val request = ChatCompletionRequest(
            model = "openrouter-model",
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = listOf(TextContent("What changed today?"))
                )
            ),
            stream = true,
            tools = adapter.toChatCompletionTools(listOf(ToolDefinition.WebSearch)),
            toolChoice = ChatCompletionToolChoice.Auto
        )

        val payload = NetworkClient.openAIJson.encodeToString(request)

        assertTrue(payload.contains(""""tools""""))
        assertTrue(payload.contains(""""type":"function""""))
        assertTrue(payload.contains(""""function":{"name":"web_search""""))
        assertTrue(payload.contains(""""additionalProperties":false"""))
        assertTrue(payload.contains(""""tool_choice":"auto""""))
    }

    @Test
    fun `chat completion tool serialization supports current datetime tool`() {
        val payload = NetworkClient.openAIJson.encodeToString(
            adapter.toChatCompletionTools(listOf(CurrentDateTimeToolProvider().definition))
        )

        assertTrue(payload.contains(""""name":"current_datetime""""))
        assertTrue(payload.contains(""""parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false}"""))
    }

    @Test
    fun `chat completion tools serialize complex supported schema`() {
        val function = adapter.toChatCompletionTools(listOf(complexSchemaToolDefinition())).single().function
        val schema = function.parameters
        val properties = schema.getValue("properties").jsonObject

        assertEquals(true, function.strict)
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
        assertFalse(properties.getValue("endpoint").jsonObject.containsKey("format"))
    }

    @Test
    fun `chat completion tool disables strict mode for optional properties`() {
        val function = adapter.toChatCompletionTools(
            listOf(complexSchemaToolDefinition(required = listOf("mode")))
        ).single().function

        assertEquals(false, function.strict)
    }

    @Test
    fun `streamed tool call deltas parse to internal tool call`() {
        val firstChunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(
            """
            {
              "choices": [
                {
                  "index": 0,
                  "delta": {
                    "tool_calls": [
                      {
                        "index": 0,
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\"query\":\"latest"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )
        val secondChunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(
            """
            {
              "choices": [
                {
                  "index": 0,
                  "delta": {
                    "tool_calls": [
                      {
                        "index": 0,
                        "function": {
                          "arguments": " Android target SDK\"}"
                        }
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ]
            }
            """.trimIndent()
        )

        val calls = adapter.toolCallsFromChunks(listOf(firstChunk, secondChunk))

        assertEquals(1, calls.size)
        assertEquals("call_1", calls.single().id)
        assertEquals("web_search", calls.single().name)
        assertEquals("""{"query":"latest Android target SDK"}""", calls.single().arguments)
    }

    @Test
    fun `empty chat completion tool array does not expose tool intent`() {
        val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(
            """
            {
              "choices": [{
                "index": 0,
                "delta": {"tool_calls": []},
                "finish_reason": "tool_calls"
              }]
            }
            """.trimIndent()
        )

        assertFalse(adapter.hasToolCallIntent(listOf(chunk)))
        assertTrue(adapter.toolCallsFromChunks(listOf(chunk)).isEmpty())
    }

    @Test
    fun `nonempty invalid chat completion call still exposes raw tool intent`() {
        val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(
            """
            {
              "choices": [{
                "index": 0,
                "delta": {
                  "tool_calls": [{
                    "index": 0,
                    "id": "call_1",
                    "function": {"name": "", "arguments": "{}"}
                  }]
                },
                "finish_reason": "tool_calls"
              }]
            }
            """.trimIndent()
        )

        assertTrue(adapter.hasToolCallIntent(listOf(chunk)))
        assertTrue(adapter.toolCallsFromChunks(listOf(chunk)).isEmpty())
    }

    @Test
    fun `streamed tool arguments stop accumulating at configured limit`() {
        val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(
            """
            {
              "choices": [{
                "index": 0,
                "delta": {
                  "tool_calls": [{
                    "index": 0,
                    "id": "call_1",
                    "function": {"name": "web_search", "arguments": "12345"}
                  }]
                }
              }]
            }
            """.trimIndent()
        )

        val exception = assertThrows(ToolArgumentsTooLargeException::class.java) {
            adapter.toolCallsFromChunks(listOf(chunk), ToolLoopConfig(maxToolArgumentChars = 4))
        }

        assertEquals("tool_arguments_too_large", exception.message)
    }

    @Test
    fun `chat completions count tool calls without argument fragments`() {
        val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(
            """
            {
              "choices": [{
                "index": 0,
                "delta": {
                  "tool_calls": [
                    {"index": 0, "id": "call_1", "function": {"name": "current_datetime"}},
                    {"index": 1, "id": "call_2", "function": {"name": "current_datetime"}}
                  ]
                }
              }]
            }
            """.trimIndent()
        )

        assertThrows(ToolCallIdentityLimitExceededException::class.java) {
            adapter.toolCallsFromChunks(listOf(chunk), ToolLoopConfig(maxToolCallsPerRound = 1))
        }
    }

    @Test
    fun `continuation messages serialize assistant tool calls and tool result`() {
        val messages = adapter.continuationMessages(
            calls = listOf(
                ToolCall(
                    id = "call_1",
                    name = "web_search",
                    arguments = """{"query":"news"}"""
                )
            ),
            results = listOf(
                ToolResult(
                    callId = "call_1",
                    name = "web_search",
                    content = "Search result",
                    metadata = mapOf("source_0_url" to "https://example.com/source"),
                    structuredContent = buildJsonObject { put("count", 1) },
                    sources = listOf(ToolSource.PublicUrl("Example", "https://example.com/source"))
                )
            )
        )

        val payload = NetworkClient.openAIJson.encodeToString(messages)

        assertTrue(payload.contains(""""role":"assistant","content":null"""))
        assertTrue(payload.contains(""""tool_calls""""))
        assertTrue(payload.contains(""""id":"call_1""""))
        assertTrue(payload.contains(""""role":"tool""""))
        assertTrue(payload.contains(""""tool_call_id":"call_1""""))
        assertTrue(payload.contains("Search result"))
        assertTrue(payload.contains("https://example.com/source"))
        assertTrue(payload.contains("structured_content"))
        assertTrue(payload.contains("public_url"))
    }
}
