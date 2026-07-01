package dev.chungjungsoo.gptmobile.data.tool

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
        assertTrue(prompt.indexOf("Name: web_search") < prompt.indexOf("Name: fetch_url"))
        assertTrue(prompt.contains("Do not use this for the user's local date"))
        assertTrue(prompt.contains(""""query":{"type":"string","description":"The concise public-web search query to run. Do not use clock/time-only queries."}"""))
        assertTrue(prompt.contains(""""required":["query"]"""))
        assertTrue(prompt.contains(""""url":{"type":"string","description":"The http or https URL to fetch."}"""))
    }

    @Test
    fun `fallback prompt discourages web search for local device state`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt()

        assertTrue(prompt.contains("Do not call web_search for the user's local date, time, timezone, device state, or app settings."))
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
