package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.tool.JsonToolCallParser
import cn.nabr.chatwithchat.data.tool.JsonToolModelOutput
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolMessage
import cn.nabr.chatwithchat.data.tool.ToolPromptBuilder
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.stickerFinalAnswerInstruction

class OpenAICompatibleJsonToolAdapter(
    private val toolPromptBuilder: ToolPromptBuilder = ToolPromptBuilder(),
    private val jsonToolCallParser: JsonToolCallParser = JsonToolCallParser()
) : ToolCallingAdapter {
    override val name: String = "openai_compatible_json"
    override val supportsNativeToolCalling: Boolean = false

    override fun renderToolDefinitions(tools: List<ToolDefinition>): String = toolPromptBuilder.formatToolDefinitions(tools)

    override fun buildToolPrompt(
        tools: List<ToolDefinition>,
        scratchpad: List<ToolMessage>,
        config: ToolLoopConfig
    ): String = toolPromptBuilder.buildJsonFallbackPrompt(
        tools = tools,
        scratchpad = scratchpad,
        config = config
    )

    override fun parseModelOutput(
        rawText: String,
        config: ToolLoopConfig
    ): Result<JsonToolModelOutput> = jsonToolCallParser.parse(rawText, config)

    override fun hasToolCallIntent(rawText: String): Boolean = jsonToolCallParser.hasToolCallIntent(rawText)

    override fun renderToolResults(
        results: List<ToolResult>,
        config: ToolLoopConfig
    ): String? = toolPromptBuilder.formatToolResults(results, config)

    override fun buildFinalAnswerPrompt(
        results: List<ToolResult>,
        draftFinalAnswer: String?,
        config: ToolLoopConfig
    ): String? {
        val formattedResults = renderToolResults(results, config) ?: return null
        return buildString {
            appendLine("已有针对用户最新请求的工具结果。")
            appendLine("仅在相关时使用；若采用网页来源，请在回答中引用来源 URL。")
            appendLine("若工具结果为 tool_permission_denied，请说明缺少的 Android 权限，并请用户开启后再重试。")
            appendLine("若请求较宽泛或不够具体但结果可用，请按最合理的默认范围直接回答，简短说明该范围，不要在给出有用内容前先追问。")
            stickerFinalAnswerInstruction(results)?.let { instruction ->
                appendLine(instruction)
            }
            draftFinalAnswer?.trim()?.takeIf { it.isNotBlank() }?.let { draft ->
                appendLine()
                appendLine("工具循环生成了以下回答草稿；将其作为参考，并自然作答：")
                appendLine(draft.clip(config.maxToolResultChars))
            }
            appendLine()
            append(formattedResults)
        }.trim()
    }

    private fun String.clip(maxChars: Int): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (length <= boundedMax) return this
        return take(boundedMax).trimEnd()
    }
}
