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
            appendLine("You may call an enabled tool before answering.")
            appendLine("Return exactly one JSON object. Never use Markdown, XML, tool tags, or bare IDs.")
            appendLine("No tool: {\"type\":\"final_answer\",\"content\":\"answer text\"}")
            appendLine("Tool: {\"type\":\"tool_calls\",\"tool_calls\":[{\"name\":\"tool_name\",\"arguments\":{}}]}")
            appendLine("Rules:")
            appendLine("- Use only an enabled tool signature below and one tool call per response.")
            appendLine("- Parameter names ending in ! are required; keep arguments concise.")
            appendLine("- If a result says tool_permission_denied, explain which Android permission is needed before retrying.")
            if (tools.any { tool -> tool.name == ToolDefinition.DiscoverTools.name }) {
                appendLine("- If the needed capability is not listed, call discover_tools first. Returned tools are enabled only in the next response; never call them in the same response.")
            }
            if (tools.any { tool -> tool.name == ToolDefinition.WebSearch.name }) {
                appendLine("- web_search: make a focused query with useful entity, date, place, and source terms; do not use it for device time or state.")
            }
            if (tools.any { tool -> tool.name == ToolDefinition.FetchUrl.name }) {
                appendLine("- fetch_url: read only a page that is useful to the answer.")
            }
            fallbackStickerToolUsageRules(tools).forEach { rule -> appendLine("- $rule") }
            stickerContinuationInstruction(priorResults)?.let { instruction ->
                appendLine("- Next sticker action: $instruction")
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
            .substringBefore('.')
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
            add("Stickers are a proactive part of your reply voice; no request is needed. In low-stakes casual conversation, when you feel a clear reaction, usually express it with one sticker. Choose what you feel like expressing, not a label for the user's mood. Search first, then send one exact returned ID. Do not skip only because stickers were not mentioned or force one into every reply. Skip proactive use in safety-sensitive, medical, legal, financial, account, payment, or error-recovery replies.")
        } else if (hasSearch) {
            add("search_stickers only finds candidates and does not display one.")
        }
        if (hasSend) {
            add("Only send_sticker displays one. Never simulate a send with text, Markdown, an ID, or a tag.")
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
            add("Treat stickers as a normal optional reply modality; the user need not request one. In low-stakes casual conversation, when you feel a clear reaction, usually express it with one sticker. Base the decision on what you feel like expressing, not a label copied from the user's mood. Never skip only because stickers were not mentioned, but do not force one into every reply. Search first, then send the best returned candidate. An explicit request requires a send; retry once only if no candidate fits. Skip proactive use in safety-sensitive, medical, legal, financial, account, payment, or error-recovery replies.")
        } else if (hasSearch) {
            add("search_stickers only discovers candidates and does not display a sticker.")
        }
        if (hasSend) {
            add("Only a successful send_sticker sends a sticker. Never guess an ID or simulate a send with text, Markdown, or an [assistant sent sticker: ...] marker. After success, answer briefly without describing or identifying the sticker.")
        }
    }
}

internal fun stickerContinuationInstruction(results: Collection<ToolResult>): String? {
    val stickerResults = results.filter { result ->
        result.name == ToolDefinition.SearchStickers.name || result.name == ToolDefinition.SendSticker.name
    }
    if (stickerResults.isEmpty()) return null

    if (stickerResults.any { result -> result.name == ToolDefinition.SendSticker.name && !result.isError }) {
        return "The sticker is queued. Do not call another sticker tool. Return a brief final answer without its ID, description, or internal marker."
    }

    val latestResult = stickerResults.last()
    val searchCount = stickerResults.count { result -> result.name == ToolDefinition.SearchStickers.name }
    return when {
        latestResult.name == ToolDefinition.SearchStickers.name &&
            !latestResult.isError &&
            latestResult.hasStickerCandidates() &&
            searchCount < MAX_STICKER_SEARCH_CALLS_PER_REQUEST ->
            "Choose the returned candidate that best expresses your own reaction and call send_sticker now. Only if none expresses it may you search once more with a different description of your reaction."
        latestResult.name == ToolDefinition.SearchStickers.name && latestResult.hasStickerCandidates() ->
            "Choose the returned candidate that best expresses your own reaction and call send_sticker now. Do not search again or answer before that call."
        latestResult.name == ToolDefinition.SearchStickers.name &&
            searchCount < MAX_STICKER_SEARCH_CALLS_PER_REQUEST ->
            "No candidate expressed your intended reaction. You may search_stickers once more with a different description of what you want to express. Do not repeat the same query."
        latestResult.name == ToolDefinition.SearchStickers.name ->
            "No sticker candidate is available. Do not call another sticker tool; answer normally without claiming a sticker was sent."
        else ->
            "The sticker was not sent. Do not call another sticker tool; answer normally without claiming otherwise."
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
        "The sticker is already queued for local rendering. Answer briefly without its ID, description, or internal marker."
    } else {
        "No sticker was sent. Do not claim or simulate a send with text, Markdown, an ID, or an internal marker."
    }
}

private fun ToolResult.hasStickerCandidates(): Boolean =
    metadata["candidate_count"]?.toIntOrNull()?.let { count -> count > 0 }
        ?: content.contains("sticker_id=")
