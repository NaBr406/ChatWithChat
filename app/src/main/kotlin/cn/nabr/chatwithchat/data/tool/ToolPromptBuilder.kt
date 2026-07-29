package cn.nabr.chatwithchat.data.tool

import kotlinx.serialization.encodeToString

class ToolPromptBuilder(
    private val maxToolDefinitionChars: Int = DEFAULT_MAX_TOOL_DEFINITION_CHARS,
    private val maxPromptChars: Int = DEFAULT_MAX_PROMPT_CHARS
) {
    fun buildJsonFallbackPrompt(
        tools: List<ToolDefinition> = emptyList(),
        scratchpad: List<ToolMessage> = emptyList(),
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): String {
        val toolManifest = formatFallbackToolManifest(tools)
        val priorResults = scratchpad.mapNotNull(ToolMessage::toolResult)
        val promptPrefix = buildString {
            appendLine("回答前可以调用已启用的工具。")
            appendLine("Return exactly one JSON object. Never use Markdown, XML, tool tags, or bare IDs.")
            appendLine("Keep every JSON key, type value, tool name, and parameter name exactly as shown; never translate them.")
            appendLine("No tool: {\"type\":\"final_answer\",\"content\":\"answer text\"}")
            appendLine("Tool: {\"type\":\"tool_calls\",\"tool_calls\":[{\"name\":\"tool_name\",\"arguments\":{}}]}")
            appendLine("Rules:")
            appendLine("- Use only an enabled tool signature below and one tool call per response.")
            appendLine("- Parameter names ending in ! are required; keep arguments concise.")
            appendLine("- 结果为 tool_permission_denied 时，说明缺少哪项 Android 权限，待用户开启后再重试。")
            if (tools.any { tool -> tool.name == ToolDefinition.DiscoverTools.name }) {
                appendLine("- 所需能力未列出时，先调用 discover_tools；返回的工具只能从下一次响应开始调用，不得在同一次响应中调用。")
            }
            if (tools.any { tool -> tool.name == ToolDefinition.WebSearch.name }) {
                appendLine("- web_search：生成聚焦的 query，加入有用的实体、日期、地点和来源词；不要查询设备时间或状态。")
            }
            if (tools.any { tool -> tool.name == ToolDefinition.FetchUrl.name }) {
                appendLine("- fetch_url：只读取对回答有帮助的网页。")
            }
            fallbackStickerToolUsageRules(tools).forEach { rule -> appendLine("- $rule") }
            stickerContinuationInstruction(
                results = priorResults,
                allowedToolNames = tools.mapTo(linkedSetOf(), ToolDefinition::name)
            )?.let { instruction ->
                appendLine("- 贴图下一步：$instruction")
            }
            appendLine()
            appendLine("Enabled tool signatures:")
            append(toolManifest)
        }.trim()

        val formattedScratchpad = formatScratchpad(scratchpad, config)
        val scratchpadPrefix = "\n\nTool scratchpad:\n"
        val remainingScratchpadChars = (maxPromptChars - promptPrefix.length - scratchpadPrefix.length).coerceAtLeast(0)
        val boundedScratchpad = formattedScratchpad
            ?.takeLast(remainingScratchpadChars)
            ?.trimStart()
            ?.takeIf { value -> value.isNotBlank() }

        return buildString {
            append(promptPrefix)
            boundedScratchpad?.let { value ->
                append(scratchpadPrefix)
                append(value)
            }
        }
    }

    fun buildJsonFinalAnswerPrompt(
        scratchpad: List<ToolMessage>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): String {
        val latestResult = scratchpad.mapNotNull(ToolMessage::toolResult).lastOrNull()
        return buildString {
            appendLine("Return exactly one JSON object. Never use Markdown, XML, tool tags, or bare IDs.")
            appendLine("Keep every JSON key and type value exactly as shown; never translate them.")
            appendLine("{\"type\":\"final_answer\",\"content\":\"answer text\"}")
            appendLine("Do not call any tool. The content is the final answer shown to the user.")
            latestResult?.let { result ->
                appendLine()
                appendLine("Latest tool outcome: ${result.name} - ${if (result.isError) "ERROR" else "OK"}")
                append(result.content.trim().clip(config.maxToolResultChars))
            }
        }.trim()
    }

    fun formatToolDefinitions(tools: List<ToolDefinition>): String = tools.joinToString(separator = "\n\n") { tool ->
        tool.toPromptText()
    }.trim()

    private fun formatFallbackToolManifest(tools: List<ToolDefinition>): String {
        val distinctTools = tools.distinctBy(ToolDefinition::name)
        val basicLines = distinctTools.map { tool -> tool.toFallbackSignature(includeSummary = false) }
        val detailedLines = distinctTools.map { tool -> tool.toFallbackSignature(includeSummary = true) }
        val descriptionBudget = maxToolDefinitionChars.coerceAtLeast(0)
        var usedDescriptionChars = 0

        return basicLines.indices.joinToString(separator = "\n") { index ->
            val basic = basicLines[index]
            val detailed = detailedLines[index]
            val addedChars = detailed.length - basic.length
            if (usedDescriptionChars + addedChars <= descriptionBudget) {
                usedDescriptionChars += addedChars
                detailed
            } else {
                basic
            }
        }
    }

    private fun ToolDefinition.toFallbackSignature(includeSummary: Boolean): String {
        val requiredNames = parameters.required.toSet()
        val parametersText = parameters.properties.entries.joinToString(separator = ", ") { (name, parameter) ->
            "$name:${parameter.toFallbackType()}${if (name in requiredNames) "!" else ""}"
        }
        val signature = "$name($parametersText)"
        if (!includeSummary) return signature

        val summary = description
            .substringBeforeSentenceEnd()
            .trim()
            .take(MAX_FALLBACK_TOOL_SUMMARY_CHARS)
            .takeIf { value -> value.isNotBlank() }
            ?: return signature
        return "$signature - $summary"
    }

    private fun ToolDefinition.Parameter.toFallbackType(): String = buildString {
        append(type)
        enumValues.takeIf { values -> values.isNotEmpty() }?.let { values ->
            append('[')
            append(values.joinToString(separator = "|"))
            append(']')
        }
        format?.takeIf { value -> value.isNotBlank() }?.let { value ->
            append('<')
            append(value)
            append('>')
        }
    }

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
                result.structuredContent?.let { structuredContent ->
                    appendLine("Structured content:")
                    appendLine(toolProtocolJson.encodeToString(structuredContent))
                }
                result.sources.forEach { source ->
                    appendLine("Source: ${toolProtocolJson.encodeToString(source)}")
                }
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
        private const val MAX_FALLBACK_TOOL_SUMMARY_CHARS = 120
    }
}

private fun fallbackStickerToolUsageRules(tools: Collection<ToolDefinition>): List<String> {
    val activeToolNames = tools.map(ToolDefinition::name).toSet()
    val hasSearch = ToolDefinition.SearchStickers.name in activeToolNames
    val hasSend = ToolDefinition.SendSticker.name in activeToolNames
    if (!hasSearch && !hasSend) return emptyList()

    return buildList {
        if (hasSearch && hasSend) {
            add("贴图表情是你回复语气的一部分，无需用户明确要求。轻松闲聊中，只要你产生了明确的自然反应，通常就用一张贴图表达；选择你自己想表达的感觉，不要照搬用户的情绪。不要因为用户没提贴图就放弃，也不要每次回复都强行发送。涉及安全、医疗、法律、财务、账号、支付或错误恢复时不要主动发送。")
            add("先调用 search_stickers，再用它返回的一个准确 sticker_id 调用 send_sticker。")
        } else if (hasSearch) {
            add("search_stickers 只返回候选，不会显示贴图。")
        }
        if (hasSend) {
            add("只有调用 send_sticker 才会显示贴图；不要用文字、Markdown、sticker_id 或标签模拟发送。")
        }
    }
}

internal fun stickerToolUsageRules(tools: Collection<ToolDefinition>): List<String> {
    val activeToolNames = tools.map(ToolDefinition::name).toSet()
    val hasSearch = ToolDefinition.SearchStickers.name in activeToolNames
    val hasSend = ToolDefinition.SendSticker.name in activeToolNames
    if (!hasSearch && !hasSend) return emptyList()

    return buildList {
        if (hasSearch && hasSend) {
            add("把贴图表情当作正常但可选的回复方式，无需用户明确要求。轻松闲聊中，只要你产生了明确的自然反应，通常就用一张贴图表达。是否发送只取决于你自己想表达什么，不要照搬用户的情绪标签。不要因为用户没提贴图就跳过，也不要每次回复都强行发送。用户明确要求时必须发送；没有合适候选时最多换一种描述重试一次。涉及安全、医疗、法律、财务、账号、支付或错误恢复时不要主动发送。")
            add("先调用 search_stickers，再用最合适候选的 sticker_id 调用 send_sticker。")
        } else if (hasSearch) {
            add("search_stickers 只返回候选，不会显示贴图。")
        }
        if (hasSend) {
            add("只有成功调用 send_sticker 才算真正发送贴图。不要猜测 sticker_id，也不要用文字、Markdown 或 [assistant sent sticker: ...] 标记模拟发送；成功后简短作答，不要描述或标识贴图。")
        }
    }
}

internal fun stickerContinuationInstruction(
    results: Collection<ToolResult>,
    allowedToolNames: Set<String>? = null
): String? {
    val stickerResults = results.filter { result ->
        result.name == ToolDefinition.SearchStickers.name || result.name == ToolDefinition.SendSticker.name
    }
    if (stickerResults.isEmpty()) return null

    if (stickerResults.any { result -> result.name == ToolDefinition.SendSticker.name && !result.isError }) {
        return "贴图已进入本地渲染队列。不要再调用贴图工具；用简短正文作答，不要提及 sticker_id、贴图描述或内部标记。"
    }

    val latestResult = stickerResults.last()
    val searchCount = stickerResults.count { result -> result.name == ToolDefinition.SearchStickers.name }
    val canSearch = allowedToolNames?.contains(ToolDefinition.SearchStickers.name) != false
    val canSend = allowedToolNames?.contains(ToolDefinition.SendSticker.name) != false
    return when {
        latestResult.name == ToolDefinition.SearchStickers.name &&
            !latestResult.isError &&
            latestResult.hasStickerCandidates() &&
            canSend &&
            !canSearch ->
            "从返回候选中选择最能表达你自身反应的一张，立即调用 send_sticker；不要再次搜索，也不要在调用前直接回答。"
        latestResult.name == ToolDefinition.SearchStickers.name &&
            !latestResult.isError &&
            latestResult.hasStickerCandidates() &&
            searchCount < MAX_STICKER_SEARCH_CALLS_PER_REQUEST ->
            "从返回候选中选择最能表达你自身反应的一张，立即调用 send_sticker。只有所有候选都不合适时，才可换一种方式描述你的反应并再调用一次 search_stickers。"
        latestResult.name == ToolDefinition.SearchStickers.name && latestResult.hasStickerCandidates() ->
            "从返回候选中选择最能表达你自身反应的一张，立即调用 send_sticker；不要再次搜索，也不要在调用前直接回答。"
        latestResult.name == ToolDefinition.SearchStickers.name &&
            canSearch &&
            searchCount < MAX_STICKER_SEARCH_CALLS_PER_REQUEST ->
            "当前没有候选能表达你的反应。可以换一种方式描述你想表达的感觉，再调用一次 search_stickers；不要重复同一 query。"
        latestResult.name == ToolDefinition.SearchStickers.name ->
            "没有可用的贴图候选。不要再调用贴图工具；正常回答，且不要声称已经发送贴图。"
        else ->
            "贴图发送失败。不要再调用贴图工具；正常回答，且不要声称已经发送。"
    }
}

internal fun stickerFinalAnswerInstruction(results: Collection<ToolResult>): String? {
    val stickerResults = results.filter { result ->
        result.name == ToolDefinition.SearchStickers.name || result.name == ToolDefinition.SendSticker.name
    }
    if (stickerResults.isEmpty()) return null

    val hasSuccessfulSend = stickerResults.any { result ->
        result.name == ToolDefinition.SendSticker.name && !result.isError
    }
    return if (hasSuccessfulSend) {
        "贴图已进入本地渲染队列。用简短正文作答，不要提及 sticker_id、贴图描述或内部标记。"
    } else {
        "没有贴图发送成功。不要用文字、Markdown、sticker_id 或内部标记声称或模拟发送。"
    }
}

internal fun String.substringBeforeSentenceEnd(): String {
    val sentenceEnd = listOf(indexOf('.'), indexOf('。'))
        .filter { index -> index >= 0 }
        .minOrNull()
        ?: return this
    return substring(0, sentenceEnd)
}
