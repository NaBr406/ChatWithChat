package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionCallOutputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseToolChoice
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.tool.CurrentDateTimeToolProvider
import dev.chungjungsoo.gptmobile.data.tool.ToolArgumentsTooLargeException
import dev.chungjungsoo.gptmobile.data.tool.ToolCallIdentityLimitExceededException
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.ToolSource
import dev.chungjungsoo.gptmobile.data.tool.complexSchemaToolDefinition
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

class OpenAIResponsesToolAdapterTest {
    private val adapter = OpenAIResponsesToolAdapter()

    @Test
    fun `openai request serializes with tools and tool choice`() {
        val request = ResponsesRequest(
            model = "gpt-5",
            input = listOf(
                ResponseInputMessage(
                    role = "user",
                    content = ResponseInputContent.text("What changed today?")
                ),
                adapter.toolResultToResponseInput(
                    ToolResult(
                        callId = "call_1",
                        name = "web_search",
                        content = "Search result",
                        metadata = mapOf("source_0_url" to "https://example.com/source")
                    )
                )
            ),
            stream = true,
            tools = adapter.toResponseTools(listOf(ToolDefinition.WebSearch)),
            toolChoice = ResponseToolChoice.Auto
        )

        val payload = NetworkClient.openAIJson.encodeToString(request)

        assertTrue(payload.contains(""""tools""""))
        assertTrue(payload.contains(""""type":"function""""))
        assertTrue(payload.contains(""""name":"web_search""""))
        assertTrue(payload.contains(""""additionalProperties":false"""))
        assertTrue(payload.contains(""""tool_choice":"auto""""))
        assertTrue(payload.contains(""""type":"function_call_output""""))
        assertTrue(payload.contains(""""call_id":"call_1""""))
    }

    @Test
    fun `openai tool serialization supports current datetime tool`() {
        val payload = NetworkClient.openAIJson.encodeToString(
            adapter.toResponseTools(listOf(CurrentDateTimeToolProvider().definition))
        )

        assertTrue(payload.contains(""""name":"current_datetime""""))
        assertTrue(payload.contains(""""parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false}"""))
    }

    @Test
    fun `openai response tools serialize complex supported schema`() {
        val tool = adapter.toResponseTools(listOf(complexSchemaToolDefinition())).single()
        val schema = tool.parameters
        val properties = schema.getValue("properties").jsonObject

        assertEquals(true, tool.strict)
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
        assertEquals("array", properties.getValue("tags").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "string",
            properties.getValue("tags").jsonObject.getValue("items").jsonObject.getValue("type").jsonPrimitive.content
        )
        assertFalse(properties.getValue("endpoint").jsonObject.containsKey("format"))
    }

    @Test
    fun `openai response tool disables strict mode for optional properties`() {
        val tool = adapter.toResponseTools(
            listOf(complexSchemaToolDefinition(required = listOf("mode")))
        ).single()

        assertEquals(false, tool.strict)
    }

    @Test
    fun `openai stream tool event parses to internal tool call`() {
        val event = NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(
            """
            {
              "type": "response.function_call_arguments.done",
              "item_id": "fc_123",
              "output_index": 0,
              "call_id": "call_1",
              "name": "web_search",
              "arguments": "{\"query\":\"latest Android target SDK\"}"
            }
            """.trimIndent()
        )

        val calls = adapter.toolCallsFromEvents(listOf(event))

        assertEquals(1, calls.size)
        assertEquals("call_1", calls.single().id)
        assertEquals("web_search", calls.single().name)
        assertEquals("""{"query":"latest Android target SDK"}""", calls.single().arguments)
    }

    @Test
    fun `openai response argument deltas enforce cumulative limit`() {
        val events = listOf("123", "45").map { delta ->
            NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(
                """
                {
                  "type": "response.function_call_arguments.delta",
                  "item_id": "fc_123",
                  "output_index": 0,
                  "delta": "$delta"
                }
                """.trimIndent()
            )
        }

        val exception = assertThrows(ToolArgumentsTooLargeException::class.java) {
            adapter.toolCallsFromEvents(events, ToolLoopConfig(maxToolArgumentChars = 4))
        }

        assertEquals("tool_arguments_too_large", exception.message)
    }

    @Test
    fun `openai response counts function calls without arguments`() {
        val events = (0..1).map { index ->
            NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(
                """
                {
                  "type": "response.output_item.done",
                  "output_index": $index,
                  "item": {
                    "type": "function_call",
                    "id": "fc_$index",
                    "call_id": "call_$index",
                    "name": "current_datetime"
                  }
                }
                """.trimIndent()
            )
        }

        assertThrows(ToolCallIdentityLimitExceededException::class.java) {
            adapter.toolCallsFromEvents(events, ToolLoopConfig(maxToolCallsPerRound = 1))
        }
    }

    @Test
    fun `openai output item fallback parses function call`() {
        val event = NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(
            """
            {
              "type": "response.output_item.done",
              "output_index": 0,
              "item": {
                "type": "function_call",
                "id": "fc_123",
                "call_id": "call_1",
                "name": "fetch_url",
                "arguments": "{\"url\":\"https://example.com\"}"
              }
            }
            """.trimIndent()
        )

        val calls = adapter.toolCallsFromEvents(listOf(event))

        assertEquals(1, calls.size)
        assertEquals("call_1", calls.single().id)
        assertEquals("fetch_url", calls.single().name)
        assertEquals("""{"url":"https://example.com"}""", calls.single().arguments)
    }

    @Test
    fun `tool result round trip is represented as function call output`() {
        val output = adapter.toolResultToResponseInput(
            ToolResult(
                callId = "call_1",
                name = "fetch_url",
                content = "Fetched content",
                isError = true,
                metadata = mapOf("url" to "https://example.com"),
                structuredContent = buildJsonObject { put("count", 1) },
                sources = listOf(ToolSource.PublicUrl("Example", "https://example.com"))
            )
        )

        val payload = NetworkClient.openAIJson.encodeToString(output)
        val decoded = NetworkClient.openAIJson.decodeFromString<ResponseFunctionCallOutputItem>(payload)

        assertEquals("function_call_output", decoded.type)
        assertEquals("call_1", decoded.callId)
        assertTrue(decoded.output.contains("Fetched content"))
        assertTrue(decoded.output.contains("https://example.com"))
        assertTrue(decoded.output.contains("structured_content"))
        assertTrue(decoded.output.contains("public_url"))
        assertTrue(decoded.output.contains(""""ok":false"""))
        assertFalse(decoded.output.contains("function_call_output"))
    }
}
