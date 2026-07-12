package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPromptBuilderTest {

    @Test
    fun `tool definitions render stable prompt text`() {
        val builder = ToolPromptBuilder()

        val prompt = builder.formatToolDefinitions(ToolDefinition.BuiltIns)

        assertTrue(prompt.contains("Name: web_search"))
        assertTrue(prompt.contains("Name: fetch_url"))
        assertTrue(prompt.contains("Name: current_datetime"))
        assertTrue(prompt.contains("Name: device_location"))
        assertTrue(prompt.indexOf("Name: web_search") < prompt.indexOf("Name: fetch_url"))
        assertTrue(prompt.indexOf("Name: fetch_url") < prompt.indexOf("Name: current_datetime"))
        assertTrue(prompt.indexOf("Name: current_datetime") < prompt.indexOf("Name: device_location"))
        assertTrue(prompt.contains("Do not use this for the user's local date"))
        assertTrue(prompt.contains(""""query":{"type":"string","description":"A concise, structured public-web search query. Include concrete dates/years, canonical names, geography, category/source terms, and official or primary-source terms when useful. Do not use clock/time-only queries."}"""))
        assertTrue(prompt.contains(""""required":["query"]"""))
        assertTrue(prompt.contains(""""url":{"type":"string","description":"The http or https URL to fetch."}"""))
        assertTrue(prompt.contains("Android system location permission"))
    }

    @Test
    fun `fallback definitions include expanded canonical schema`() {
        val prompt = ToolPromptBuilder().formatToolDefinitions(listOf(complexSchemaToolDefinition()))

        assertTrue(prompt.contains(""""additionalProperties":false"""))
        assertTrue(prompt.contains(""""enum":["safe","fast"]"""))
        assertTrue(prompt.contains(""""items":{"type":"string"""))
        assertTrue(prompt.contains(""""minimum":0.0"""))
        assertTrue(prompt.contains(""""maximum":5.0"""))
        assertTrue(prompt.contains(""""minLength":1"""))
        assertTrue(prompt.contains(""""maxLength":20"""))
        assertTrue(prompt.contains(""""format":"uri"""))
    }

    @Test
    fun `fallback example uses schema compatible nested argument types`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(complexSchemaToolDefinition())
        )
        val example = prompt.lineSequence().first { line -> line.startsWith("{\"type\":\"tool_calls\"") }
        val output = JsonToolCallParser().parse(example).getOrThrow() as JsonToolModelOutput.ToolCalls
        val arguments = output.calls.single().argumentsObject().getOrThrow()

        assertEquals("safe", arguments.getValue("mode").jsonPrimitive.content)
        assertEquals(false, arguments.getValue("options").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
        assertEquals(0, arguments.getValue("retries").jsonPrimitive.int)
        assertEquals("https://example.com", arguments.getValue("endpoint").jsonPrimitive.content)
        assertEquals("value", arguments.getValue("tags").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `fallback prompt discourages web search for local device state`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(tools = listOf(ToolDefinition.WebSearch))

        assertTrue(prompt.contains("Do not call web_search for the user's local date, time, timezone, device state, or app settings."))
    }

    @Test
    fun `fallback prompt tells model how to handle permission denied tool errors`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt()

        assertTrue(prompt.contains("tool_permission_denied"))
        assertTrue(prompt.contains("which Android permission is missing"))
    }

    @Test
    fun `fallback prompt requires structured search query planning`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(tools = listOf(ToolDefinition.WebSearch))

        assertTrue(prompt.contains("rewrite the user's request into a search-engine query"))
        assertTrue(prompt.contains("Resolve relative dates such as today, yesterday"))
        assertTrue(prompt.contains("choose sensible default scopes and complementary queries"))
        assertTrue(prompt.contains("Prefer official, primary, or local-language source terms"))
    }

    @Test
    fun `fallback prompt only names active non search tools`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(
                ToolDefinition(
                    name = "current_datetime",
                    description = "Returns the current date and time.",
                    parameters = ToolDefinition.Parameters()
                )
            )
        )

        assertTrue(prompt.contains("current_datetime"))
        assertTrue(prompt.contains("Available tools:"))
        assertFalse(prompt.contains("web_search"))
        assertFalse(prompt.contains("fetch_url"))
    }

    @Test
    fun `fallback tool call json parses successfully`() {
        val result = ToolCall.parseFallbackCalls(
            """
            {"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"latest Android SDK"}}]}
            """.trimIndent()
        )

        assertTrue(result.isSuccess)
        val calls = result.getOrThrow()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls.first().id)
        assertEquals("web_search", calls.first().name)
        assertEquals("""{"query":"latest Android SDK"}""", calls.first().arguments)
        assertEquals("latest Android SDK", calls.first().argumentsObject().getOrThrow()["query"].toString().trim('"'))
    }

    @Test
    fun `fallback parser preserves nested objects and array items`() {
        val result = ToolCall.parseFallbackCalls(
            """
            {
              "type":"tool_calls",
              "tool_calls":[
                {
                  "id":"call_nested",
                  "name":"complex_tool",
                  "arguments":{"options":{"enabled":true},"tags":["one","two"]}
                }
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        val arguments = result.single().argumentsObject().getOrThrow()
        assertTrue(arguments.getValue("options").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("one", "two"),
            arguments.getValue("tags").jsonArray.map { value -> value.jsonPrimitive.content }
        )
    }

    @Test
    fun `malformed tool call json returns failure`() {
        val result = ToolCall.parseFallbackCalls("""{"type":"tool_calls","tool_calls":["broken"]""")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("tool_call_json_not_found"))
    }

    @Test
    fun `tool result formatting is deterministic and bounded`() {
        val builder = ToolPromptBuilder()
        val prompt = builder.formatToolResults(
            listOf(
                ToolResult(
                    callId = "call_2",
                    name = "fetch_url",
                    content = "abcdef",
                    isError = false,
                    metadata = mapOf("url" to "https://example.com", "title" to "Example")
                ),
                ToolResult(
                    callId = "call_1",
                    name = "web_search",
                    content = "x".repeat(50),
                    isError = true
                )
            ),
            ToolLoopConfig(maxToolResultChars = 8, maxScratchpadChars = 220)
        ).orEmpty()

        assertTrue(prompt.contains("1. fetch_url (call_2) - OK"))
        assertTrue(prompt.indexOf("Metadata title: Example") < prompt.indexOf("Metadata url: https://example.com"))
        assertTrue(prompt.contains("2. web_search (call_1) - ERROR"))
        assertTrue(prompt.contains("xxxxxxxx"))
        assertFalse(prompt.contains("x".repeat(9)))
        assertTrue(prompt.length <= 220)
    }

    @Test
    fun `tool result formatting honors total injection limit`() {
        val builder = ToolPromptBuilder()
        val prompt = builder.formatToolResults(
            listOf(
                ToolResult(
                    callId = "call_1",
                    name = "web_search",
                    content = "a".repeat(500)
                ),
                ToolResult(
                    callId = "call_2",
                    name = "fetch_url",
                    content = "b".repeat(500)
                )
            ),
            ToolLoopConfig(
                maxToolResultChars = 500,
                maxScratchpadChars = 1_000,
                maxTotalToolResultChars = 120
            )
        ).orEmpty()

        assertTrue(prompt.length <= 120)
    }
}
