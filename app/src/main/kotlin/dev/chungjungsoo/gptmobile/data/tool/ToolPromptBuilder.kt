package dev.chungjungsoo.gptmobile.data.tool

class ToolPromptBuilder(
    private val maxToolDefinitionChars: Int = DEFAULT_MAX_TOOL_DEFINITION_CHARS,
    private val maxPromptChars: Int = DEFAULT_MAX_PROMPT_CHARS
) {
    fun buildJsonFallbackPrompt(
        tools: List<ToolDefinition> = ToolDefinition.BuiltIns,
        scratchpad: List<ToolMessage> = emptyList(),
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): String {
        val prompt = buildString {
            appendLine("You may call tools before answering.")
            appendLine("Return only valid JSON. Do not use markdown fences or add extra text.")
            appendLine()
            appendLine("If no tool is needed, return:")
            appendLine("""{"type":"final_answer","content":"answer text"}""")
            appendLine()
            appendLine("If tools are needed, return:")
            appendLine("""{"type":"tool_calls","tool_calls":[{"id":"call_1","name":"web_search","arguments":{"query":"search query"}}]}""")
            appendLine()
            appendLine("Rules:")
            appendLine("- Use only the listed tool names.")
            appendLine("- Use at most ${config.maxToolCallsPerRound.coerceAtLeast(0)} tool calls in one response.")
            appendLine("- Use at most ${config.maxToolRounds.coerceAtLeast(0)} tool rounds before returning final_answer.")
            appendLine("- Keep arguments concise and match each tool parameter schema.")
            appendLine("- After web_search results, use fetch_url only for pages that are clearly worth reading.")
            appendLine("- Prefer final_answer when the existing conversation is enough.")
            appendLine()
            appendLine("Available tools:")
            appendLine(formatToolDefinitions(tools).clip(maxToolDefinitionChars))
            formatScratchpad(scratchpad, config)?.let { scratchpadText ->
                appendLine()
                appendLine("Tool scratchpad:")
                appendLine(scratchpadText)
            }
        }.trim()

        return prompt.clip(maxPromptChars)
    }

    fun formatToolDefinitions(tools: List<ToolDefinition>): String = tools.joinToString(separator = "\n\n") { tool ->
        tool.toPromptText()
    }.trim()

    fun formatToolResults(
        results: List<ToolResult>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): String? {
        if (results.isEmpty()) return null

        val text = buildString {
            appendLine("Tool results:")
            results.forEachIndexed { index, result ->
                appendLine()
                appendLine("${index + 1}. ${result.name} (${result.callId}) - ${if (result.isError) "ERROR" else "OK"}")
                if (result.metadata.isNotEmpty()) {
                    result.metadata.toSortedMap().forEach { (key, value) ->
                        appendLine("Metadata $key: $value")
                    }
                }
                appendLine("Content:")
                appendLine(result.content.trim().clip(config.maxToolResultChars))
            }
        }.trim()

        return text.clip(config.toolResultInjectionLimit()).takeIf { it.isNotBlank() }
    }

    private fun formatScratchpad(
        scratchpad: List<ToolMessage>,
        config: ToolLoopConfig
    ): String? {
        if (scratchpad.isEmpty()) return null

        val text = buildString {
            scratchpad.forEachIndexed { index, message ->
                appendLine("${index + 1}. ${message.role.name}:")
                when {
                    message.toolCall != null -> {
                        appendLine("Tool call: ${message.toolCall.name} (${message.toolCall.id})")
                        appendLine("Arguments: ${message.toolCall.arguments.clip(config.maxToolResultChars)}")
                    }
                    message.toolResult != null -> {
                        appendLine("Tool result: ${message.toolResult.name} (${message.toolResult.callId})")
                        appendLine("Status: ${if (message.toolResult.isError) "ERROR" else "OK"}")
                        appendLine(message.toolResult.content.trim().clip(config.maxToolResultChars))
                    }
                    else -> appendLine(message.content.trim().clip(config.maxToolResultChars))
                }
            }
        }.trim()

        return text.clip(config.toolResultInjectionLimit()).takeIf { it.isNotBlank() }
    }

    private fun ToolLoopConfig.toolResultInjectionLimit(): Int = minOf(
        maxScratchpadChars,
        maxTotalToolResultChars
    ).coerceAtLeast(0)

    private fun String.clip(maxChars: Int): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (length <= boundedMax) return this
        return take(boundedMax).trimEnd()
    }

    companion object {
        private const val DEFAULT_MAX_TOOL_DEFINITION_CHARS = 4_000
        private const val DEFAULT_MAX_PROMPT_CHARS = 12_000
    }
}
