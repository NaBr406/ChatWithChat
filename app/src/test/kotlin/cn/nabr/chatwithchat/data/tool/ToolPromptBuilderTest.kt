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
        assertTrue(prompt.contains("不要用它查询用户设备的本地日期"))
        assertTrue(prompt.contains("\"query\":{\"type\":\"string\",\"description\":\"简洁、结构化的公开网页搜索 query"))
        assertTrue(prompt.contains(""""required":["query"]"""))
        assertTrue(prompt.contains("\"url\":{\"type\":\"string\",\"description\":\"要获取的 http 或 https URL。\"}"))
        assertTrue(prompt.contains("Android 系统定位权限"))
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
    fun `fallback prompt uses Chinese behavior while preserving the English JSON protocol`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(ToolDefinition.SearchStickers, ToolDefinition.SendSticker)
        )

        assertTrue(prompt.contains("回答前可以调用已启用的工具"))
        assertTrue(prompt.contains("\"type\":\"final_answer\",\"content\":\"answer text\""))
        assertTrue(prompt.contains("\"type\":\"tool_calls\",\"tool_calls\""))
        assertTrue(prompt.contains("search_stickers(query:string!, limit:integer)"))
        assertTrue(prompt.contains("send_sticker(sticker_id:string!)"))
        assertTrue(prompt.contains("never translate them"))
        assertFalse(prompt.contains("\"类型\""))
        assertFalse(prompt.contains("\"工具调用\""))
        assertFalse(prompt.contains("贴图_id"))
    }

    @Test
    fun `final only json prompt carries only the latest tool outcome`() {
        val prompt = ToolPromptBuilder().buildJsonFinalAnswerPrompt(
            scratchpad = listOf(
                ToolMessage.toolResult(
                    ToolResult(
                        callId = "send_1",
                        name = ToolDefinition.SendSticker.name,
                        content = "Sticker queued for local rendering."
                    )
                )
            )
        )

        assertTrue(prompt.contains("\"type\":\"final_answer\",\"content\":\"answer text\""))
        assertTrue(prompt.contains("The content is the final answer shown to the user."))
        assertTrue(prompt.contains("Sticker queued for local rendering."))
        assertFalse(prompt.contains("tool_calls"))
        assertFalse(prompt.contains("Enabled tool signatures:"))
    }

    @Test
    fun `fallback prompt discourages web search for local device state`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(tools = listOf(ToolDefinition.WebSearch))

        assertTrue(prompt.contains("不要查询设备时间或状态"))
    }

    @Test
    fun `fallback prompt tells model how to handle permission denied tool errors`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt()

        assertTrue(prompt.contains("tool_permission_denied"))
        assertTrue(prompt.contains("说明缺少哪项 Android 权限"))
    }

    @Test
    fun `fallback prompt tells the model to discover hidden capabilities first`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(ToolDefinition.DiscoverTools)
        )

        assertTrue(prompt.contains("所需能力未列出时，先调用 discover_tools"))
        assertTrue(prompt.contains("只能从下一次响应开始调用"))
        assertTrue(prompt.contains("不得在同一次响应中调用"))
    }

    @Test
    fun `fallback prompt requires a real sticker send and forbids marker imitation`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(ToolDefinition.SearchStickers, ToolDefinition.SendSticker)
        )

        assertTrue(prompt.contains("贴图表情是你回复语气的一部分"))
        assertTrue(prompt.contains("无需用户明确要求"))
        assertTrue(prompt.contains("通常就用一张贴图表达"))
        assertTrue(prompt.contains("不要因为用户没提贴图就放弃"))
        assertTrue(prompt.contains("先调用 search_stickers"))
        assertTrue(prompt.contains("准确 sticker_id 调用 send_sticker"))
        assertTrue(prompt.contains("不要每次回复都强行发送"))
        assertTrue(prompt.contains("涉及安全、医疗、法律、财务、账号、支付或错误恢复时不要主动发送"))
        assertTrue(prompt.contains("只有调用 send_sticker 才会显示贴图"))
        assertTrue(prompt.contains("不要用文字、Markdown、sticker_id 或标签模拟发送"))
    }

    @Test
    fun `native sticker guidance encourages proactive self expression without forcing every reply`() {
        val prompt = stickerToolUsageRules(
            listOf(ToolDefinition.SearchStickers, ToolDefinition.SendSticker)
        ).joinToString(separator = " ")

        assertTrue(prompt.contains("正常但可选的回复方式"))
        assertTrue(prompt.contains("通常就用一张贴图表达"))
        assertTrue(prompt.contains("不要因为用户没提贴图就跳过"))
        assertTrue(prompt.contains("只取决于你自己想表达什么"))
        assertTrue(prompt.contains("不要每次回复都强行发送"))
        assertTrue(prompt.contains("涉及安全、医疗、法律、财务、账号、支付或错误恢复时不要主动发送"))
        assertTrue(prompt.contains("先调用 search_stickers"))
        assertTrue(prompt.contains("sticker_id 调用 send_sticker"))
    }

    @Test
    fun `fallback sticker candidate directs the next round to send sticker`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(
            tools = listOf(ToolDefinition.SearchStickers, ToolDefinition.SendSticker),
            scratchpad = listOf(
                ToolMessage.toolResult(
                    ToolResult(
                        callId = "search_1",
                        name = ToolDefinition.SearchStickers.name,
                        content = "sticker_id=builtin.reactions.celebrate",
                        metadata = mapOf("candidate_count" to "1")
                    )
                )
            )
        )

        assertTrue(prompt.contains("贴图下一步："))
        assertTrue(prompt.contains("立即调用 send_sticker"))
        assertTrue(prompt.contains("sticker_id=builtin.reactions.celebrate"))
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

        assertTrue(stickerContinuationInstruction(firstCandidates).orEmpty().contains("你自身反应"))
        assertTrue(stickerContinuationInstruction(firstCandidates).orEmpty().contains("立即调用 send_sticker"))
        assertTrue(stickerContinuationInstruction(firstCandidates).orEmpty().contains("再调用一次 search_stickers"))
        assertTrue(stickerContinuationInstruction(secondCandidates).orEmpty().contains("不要再次搜索"))
        assertTrue(stickerContinuationInstruction(firstEmpty).orEmpty().contains("再调用一次 search_stickers"))
        assertTrue(stickerContinuationInstruction(secondEmpty).orEmpty().contains("没有可用的贴图候选"))
    }

    @Test
    fun `fallback summaries stop at Chinese sentence boundaries`() {
        val tool = ToolDefinition(
            name = "localized_tool",
            description = "第一句摘要。第二句不应出现。",
            parameters = ToolDefinition.Parameters()
        )

        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(tools = listOf(tool))

        assertTrue(prompt.contains("localized_tool() - 第一句摘要"))
        assertFalse(prompt.contains("第二句不应出现"))
    }

    @Test
    fun `fallback prompt keeps the web search guidance compact`() {
        val prompt = ToolPromptBuilder().buildJsonFallbackPrompt(tools = listOf(ToolDefinition.WebSearch))

        assertTrue(prompt.contains("生成聚焦的 query"))
        assertTrue(prompt.contains("实体、日期、地点和来源词"))
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
