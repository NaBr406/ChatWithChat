package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.common.Role
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.request.GoogleToolConfig
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
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
        assertTrue(payload.contains(""""additionalProperties":false"""))
        assertTrue(payload.contains(""""toolConfig":{"functionCallingConfig":{"mode":"AUTO"}}"""))
    }

    @Test
    fun `google tool serialization supports current datetime tool`() {
        val payload = NetworkClient.json.encodeToString(
            adapter.toGoogleTools(listOf(CurrentDateTimeToolProvider().definition))
        )

        assertTrue(payload.contains(""""name":"current_datetime""""))
        assertTrue(payload.contains(""""parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false}"""))
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
                    metadata = mapOf("source_0_url" to "https://example.com/source")
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
    }
}
