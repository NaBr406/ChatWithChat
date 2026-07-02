package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionToolChoice
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.tool.CurrentDateTimeToolProvider
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
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
                    metadata = mapOf("source_0_url" to "https://example.com/source")
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
    }
}
