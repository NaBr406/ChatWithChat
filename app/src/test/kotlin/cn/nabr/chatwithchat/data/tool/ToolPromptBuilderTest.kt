package cn.nabr.chatwithchat.data.tool

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
    fun `fallback manifest advertises compact parameter signatures`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(complexSchemaToolDefinition())
        )

        assertTrue(prompt.contains("complex_tool(mode:string[safe|fast]!, options:object!, tags:array!, retries:integer!, endpoint:string<uri>!)"))
        assertFalse(prompt.contains("\"additionalProperties\""))
        assertFalse(prompt.contains("\"minimum\""))
    }

    @Test
    fun `fallback prompt discourages web search for local device state`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(tools = listOf(ToolDefinition.WebSearch))

        assertTrue(prompt.contains("do not use it for device time or state"))
    }

    @Test
    fun `fallback prompt tells model how to handle permission denied tool errors`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt()

        assertTrue(prompt.contains("tool_permission_denied"))
        assertTrue(prompt.contains("which Android permission is needed"))
    }

    @Test
    fun `fallback prompt tells the model to discover hidden capabilities first`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(ToolDefinition.DiscoverTools)
        )

        assertTrue(prompt.contains("If the needed capability is not listed, call discover_tools first"))
        assertTrue(prompt.contains("enabled only in the next response"))
        assertTrue(prompt.contains("never call them in the same response"))
    }

    @Test
    fun `fallback prompt requires a real sticker send and forbids marker imitation`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(ToolDefinition.SearchStickers, ToolDefinition.SendSticker)
        )

        assertTrue(prompt.contains("Stickers express your own response"))
        assertTrue(prompt.contains("Search first, then send one exact returned ID"))
        assertTrue(prompt.contains("never mirror the user's mood by default"))
        assertTrue(prompt.contains("Only send_sticker displays one"))
        assertTrue(prompt.contains("Never simulate a send with text"))
    }

    @Test
    fun `sticker continuation moves candidates to send while bounding retries`() {
        val firstCandidates = listOf(
            ToolResult(
                callId = "search_1",
                name = ToolDefinition.SearchStickers.name,
                content = "sticker_id=builtin.reactions.one",
                metadata = mapOf("candidate_count" to "1")
            )
        )
        val secondCandidates = firstCandidates + ToolResult(
            callId = "search_2",
            name = ToolDefinition.SearchStickers.name,
            content = "sticker_id=builtin.reactions.two",
            metadata = mapOf("candidate_count" to "1")
        )
        val firstEmpty = listOf(
            ToolResult(
                callId = "search_empty_1",
                name = ToolDefinition.SearchStickers.name,
                content = "No sticker candidates found.",
                metadata = mapOf("candidate_count" to "0")
            )
        )
        val secondEmpty = firstEmpty + firstEmpty.single().copy(callId = "search_empty_2")

        assertTrue(stickerContinuationInstruction(firstCandidates).orEmpty().contains("your own reaction"))
        assertTrue(stickerContinuationInstruction(firstCandidates).orEmpty().contains("call send_sticker now"))
        assertTrue(stickerContinuationInstruction(firstCandidates).orEmpty().contains("search once more"))
        assertTrue(stickerContinuationInstruction(secondCandidates).orEmpty().contains("Do not search again"))
        assertTrue(stickerContinuationInstruction(firstEmpty).orEmpty().contains("may search_stickers once more"))
        assertTrue(stickerContinuationInstruction(secondEmpty).orEmpty().contains("No sticker candidate is available"))
    }

    @Test
    fun `fallback prompt keeps the web search guidance compact`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(tools = listOf(ToolDefinition.WebSearch))

        assertTrue(prompt.contains("make a focused query"))
        assertTrue(prompt.contains("entity, date, place, and source terms"))
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
        assertTrue(prompt.contains("Enabled tool signatures:"))
        assertFalse(prompt.contains("web_search"))
        assertFalse(prompt.contains("fetch_url"))
    }

    @Test
    fun `fallback manifest advertises every enabled tool without silently dropping schemas`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(tools = ToolDefinition.BuiltIns)
        val definitions = prompt.substringAfter("Enabled tool signatures:")

        ToolDefinition.BuiltIns.forEach { tool ->
            assertTrue(definitions.contains("${tool.name}("))
        }
    }

    @Test
    fun `fallback manifest retains all signatures after its description budget is exhausted`() {
        val tools = (1..100).map { index ->
            ToolDefinition(
                name = "tool_$index",
                description = "A deliberately verbose description that must not decide whether tool_$index is advertised.",
                parameters = ToolDefinition.Parameters(
                    properties = mapOf("value" to ToolDefinition.Parameter(type = "string")),
                    required = listOf("value")
                )
            )
        }

        val prompt = ToolPromptBuilder(
            maxToolDefinitionChars = 0,
            maxPromptChars = 12_000
        ).buildJsonFallbackPrompt(tools = tools)

        tools.forEach { tool ->
            assertTrue(prompt.contains("${tool.name}(value:string!)"))
        }
        assertFalse(prompt.contains("deliberately verbose description"))
    }

    @Test
    fun `fallback prompt preserves the latest scratchpad result within its total budget`() {
        val prompt = ToolPromptBuilder(maxPromptChars = 700).buildJsonFallbackPrompt(
            tools = listOf(ToolDefinition.CurrentDateTime),
            scratchpad = listOf(
                ToolMessage.toolResult(
                    ToolResult(
                        callId = "first",
                        name = ToolDefinition.CurrentDateTime.name,
                        content = "first-result-" + "x".repeat(500)
                    )
                ),
                ToolMessage.toolResult(
                    ToolResult(
                        callId = "latest",
                        name = ToolDefinition.CurrentDateTime.name,
                        content = "y".repeat(500) + "latest-result"
                    )
                )
            )
        )

        assertTrue(prompt.contains("latest-result"))
        assertFalse(prompt.contains("first-result-"))
        assertTrue(prompt.length <= 700)
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
