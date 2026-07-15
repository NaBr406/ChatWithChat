package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.dto.google.common.Content
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.google.common.Role
import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.google.request.GoogleToolConfig
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
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

class GoogleNativeToolAdapterTest {
    private val adapter = GoogleNativeToolAdapter()

    @Test
    fun `google request serializes with function declarations and tool config`() {
        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    role = Role.USER,
                    parts = listOf(Part.text("What changed today?"))
                )
            ),
            tools = adapter.toGoogleTools(listOf(ToolDefinition.WebSearch)),
            toolConfig = GoogleToolConfig.Auto
        )

        val payload = NetworkClient.json.encodeToString(request)

        assertTrue(payload.contains(""""tools""""))
        assertTrue(payload.contains(""""functionDeclarations""""))
        assertTrue(payload.contains(""""name":"web_search""""))
        assertFalse(payload.contains(""""additionalProperties"""))
        assertTrue(payload.contains(""""toolConfig":{"functionCallingConfig":{"mode":"AUTO"}}"""))
    }

    @Test
    fun `google tool serialization supports current datetime tool`() {
        val payload = NetworkClient.json.encodeToString(
            adapter.toGoogleTools(listOf(CurrentDateTimeToolProvider().definition))
        )

        assertTrue(payload.contains(""""name":"current_datetime""""))
        assertTrue(payload.contains(""""parameters":{"type":"object","properties":{},"required":[]}"""))
    }

    @Test
    fun `google tools project complex schema without unsupported additional properties`() {
        val schema = adapter.toGoogleTools(listOf(complexSchemaToolDefinition()))
            .single()
            .functionDeclarations
            .single()
            .parameters
        val properties = schema.getValue("properties").jsonObject

        assertFalse(schema.containsKey("additionalProperties"))
        assertEquals(
            listOf("mode", "options", "tags", "retries", "endpoint"),
            schema.getValue("required").jsonArray.map { value -> value.jsonPrimitive.content }
        )
        assertEquals(
            listOf("safe", "fast"),
            properties.getValue("mode").jsonObject.getValue("enum").jsonArray.map { value -> value.jsonPrimitive.content }
        )
        assertEquals("object", properties.getValue("options").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(properties.getValue("options").jsonObject.containsKey("additionalProperties"))
        assertEquals(
            "string",
            properties.getValue("tags").jsonObject.getValue("items").jsonObject.getValue("type").jsonPrimitive.content
        )
        assertEquals("uri", properties.getValue("endpoint").jsonObject.getValue("format").jsonPrimitive.content)
    }

    @Test
    fun `function call response parses to internal tool call`() {
        val response = NetworkClient.json.decodeFromString<GenerateContentResponse>(
            """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "functionCall": {
                          "id": "func_1",
                          "name": "web_search",
                          "args": {
                            "query": "latest Android target SDK"
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val calls = adapter.toolCallsFromResponses(listOf(response))

        assertEquals(1, calls.size)
        assertEquals("func_1", calls.single().id)
        assertEquals("web_search", calls.single().name)
        assertEquals("""{"query":"latest Android target SDK"}""", calls.single().arguments)
    }

    @Test
    fun `google function arguments enforce configured limit before tool call creation`() {
        val response = NetworkClient.json.decodeFromString<GenerateContentResponse>(
            """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [{
                    "functionCall": {
                      "id": "func_1",
                      "name": "web_search",
                      "args": {"query": "oversized"}
                    }
                  }]
                }
              }]
            }
            """.trimIndent()
        )

        val exception = assertThrows(ToolArgumentsTooLargeException::class.java) {
            adapter.toolCallsFromResponses(listOf(response), ToolLoopConfig(maxToolArgumentChars = 8))
        }

        assertEquals("tool_arguments_too_large", exception.message)
    }

    @Test
    fun `google counts every function call occurrence`() {
        val response = NetworkClient.json.decodeFromString<GenerateContentResponse>(
            """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [
                    {"functionCall": {"id": "same", "name": "current_datetime", "args": {}}},
                    {"functionCall": {"id": "same", "name": "current_datetime", "args": {}}}
                  ]
                }
              }]
            }
            """.trimIndent()
        )

        assertThrows(ToolCallIdentityLimitExceededException::class.java) {
            adapter.toolCallsFromResponses(listOf(response), ToolLoopConfig(maxToolCallsPerRound = 1))
        }
    }

    @Test
    fun `continuation contents serialize function call and function response`() {
        val contents = adapter.continuationContents(
            calls = listOf(
                ToolCall(
                    id = "func_1",
                    name = "web_search",
                    arguments = """{"query":"news"}"""
                )
            ),
            results = listOf(
                ToolResult(
                    callId = "func_1",
                    name = "web_search",
                    content = "Search result",
                    metadata = mapOf("source_0_url" to "https://example.com/source"),
                    structuredContent = buildJsonObject { put("count", 1) },
                    sources = listOf(ToolSource.PublicUrl("Example", "https://example.com/source"))
                )
            )
        )

        val payload = NetworkClient.json.encodeToString(contents)

        assertTrue(payload.contains(""""role":"model""""))
        assertTrue(payload.contains(""""functionCall""""))
        assertTrue(payload.contains(""""id":"func_1""""))
        assertTrue(payload.contains(""""role":"user""""))
        assertTrue(payload.contains(""""functionResponse""""))
        assertTrue(payload.contains("Search result"))
        assertTrue(payload.contains("https://example.com/source"))
        assertTrue(payload.contains("structured_content"))
        assertTrue(payload.contains("public_url"))
    }
}
