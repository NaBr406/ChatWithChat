package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.tool.JsonToolCallParser
import cn.nabr.chatwithchat.data.tool.JsonToolModelOutput
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolMessage
import cn.nabr.chatwithchat.data.tool.ToolPromptBuilder
import cn.nabr.chatwithchat.data.tool.ToolResult

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
            appendLine("Tool results are available for the latest user request.")
            appendLine("Use them only when relevant. If you use web sources, cite the source URLs in the answer.")
            appendLine("If a tool result reports tool_permission_denied, explain the missing Android permission and ask the user to enable it before retrying.")
            appendLine("If the user's request is broad or underspecified but the tool results are usable, answer with the most reasonable default scope, state that scope briefly, and avoid asking a clarifying question before giving useful content.")
            draftFinalAnswer?.trim()?.takeIf { it.isNotBlank() }?.let { draft ->
                appendLine()
                appendLine("The tool loop drafted this final answer. Use it as guidance, but answer naturally:")
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
