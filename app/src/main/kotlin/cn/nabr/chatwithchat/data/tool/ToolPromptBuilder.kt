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
            // The old wording was "one tool call per response", which contradicted
            // `ToolLoopConfig.maxToolCallsPerRound` and the parser, both of which have always handled a
            // batch. It cost a whole round per independent lookup for no reason.
            appendLine(
                "- Use only an enabled tool signature below. 相互独立的调用可以放进同一次响应的 tool_calls " +
                    "数组（最多 ${config.maxToolCallsPerRound.coerceAtLeast(1)} 个）；" +
                    "后一步要用到前一步的结果时，必须留到下一次响应。"
            )
            appendLine("- Parameter names ending in ! are required; keep arguments concise.")
            appendLine("- 结果为 tool_permission_denied 时，说明缺少哪项 Android 权限，待用户开启后再重试。")
            if (tools.any { tool -> tool.name == ToolDefinition.DiscoverTools.name }) {
                appendLine("- 所需能力未列出时，先调用 discover_tools；返回的工具只能从下一次响应开始调用，不得在同一次响应中调用。")
            }
            if (tools.any { tool -> tool.name == ToolDefinition.WebSearch.name }) {
                appendLine("- web_search：生成聚焦的 query，加入有用的实体、日期、地点和来源词；不要查询设备时间或状态。")
            }
            toolSelectionPriorityRules(tools).forEach { rule -> appendLine("- $rule") }
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

        val scratchpadPrefix = "\n\nTool scratchpad:\n"
        val scratchpadBudget = minOf(
            config.toolResultInjectionLimit(),
            maxPromptChars - promptPrefix.length - scratchpadPrefix.length
        ).coerceAtLeast(0)
        val boundedScratchpad = formatScratchpad(scratchpad, config, scratchpadBudget)
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
                result.visualAttachments.forEach { attachment ->
                    appendLine(
                        "Visual: ref=${attachment.visualRef ?: attachment.assetId}; " +
                            "profile=${attachment.profile.storageValue}; mime=${attachment.mimeType}; " +
                            "size=${attachment.sizeBytes}"
                    )
                }
            }
        }.trim()

        return text.clip(config.toolResultInjectionLimit()).takeIf { it.isNotBlank() }
    }

    /**
     * Renders the scratchpad to fit [budgetChars], newest step first.
     *
     * The previous version cut from both ends: [formatScratchpad] clipped the head off its own limit and the
     * caller then applied `takeLast` to what survived, so what reached the model was a window out of the
     * middle. One catalog-sized MCP result was enough to push every earlier step out of it, and the earlier
     * steps are exactly what a chain needs — a basket cannot be priced from a menu if the `store_id` fetched
     * two rounds ago is gone. The budget itself was part of the bug: a single result may be 32,000 chars
     * against a 16,000-char scratchpad, so overflow was the normal case rather than the exception.
     *
     * Now every step is rendered twice, and the newest steps are kept in full while older ones degrade to a
     * one-line summary before anything is dropped at all. The progress digest goes first and outlives every
     * entry, so even a fully evicted scratchpad still says which tools have already run and how they ended.
     */
    private fun formatScratchpad(
        scratchpad: List<ToolMessage>,
        config: ToolLoopConfig,
        budgetChars: Int
    ): String? {
        if (scratchpad.isEmpty() || budgetChars <= 0) return null

        val entries = scratchpad.map { message -> message.toScratchpadEntry(config) }
        val digest = scratchpad.progressDigest()
        val kept = ArrayDeque<String>()
        var usedChars = digest?.let { value -> value.length + ENTRY_SEPARATOR.length } ?: 0
        var droppedEntries = 0

        for (entry in entries.asReversed()) {
            val full = entry.full.fitCost(usedChars)
            val summary = entry.summary.fitCost(usedChars)
            when {
                full <= budgetChars -> {
                    kept.addFirst(entry.full)
                    usedChars = full
                }
                summary <= budgetChars -> {
                    kept.addFirst(entry.summary)
                    usedChars = summary
                }
                else -> {
                    // Stop at the first step that will not fit even as a summary: keeping recency contiguous
                    // matters more than squeezing in an older step that happens to be shorter.
                    droppedEntries = entries.size - kept.size
                    break
                }
            }
        }

        // Not even the newest step fits. Its tail is still the most useful thing to send, since that is
        // where a tool result carries its outcome.
        if (kept.isEmpty()) return entries.last().full.takeLast(budgetChars)

        if (droppedEntries > 0) {
            while (usedChars + EARLIER_ENTRIES_OMITTED.length + ENTRY_SEPARATOR.length > budgetChars &&
                kept.size > 1
            ) {
                usedChars -= kept.removeFirst().length + ENTRY_SEPARATOR.length
            }
            kept.addFirst(EARLIER_ENTRIES_OMITTED)
        }
        digest?.let { value -> kept.addFirst(value) }

        return kept.joinToString(separator = ENTRY_SEPARATOR).takeIf { value -> value.isNotBlank() }
    }

    private fun String.fitCost(usedChars: Int): Int = usedChars + length + ENTRY_SEPARATOR.length

    private fun ToolMessage.toScratchpadEntry(config: ToolLoopConfig): ScratchpadEntry = when {
        toolCall != null -> ScratchpadEntry(
            full = buildString {
                appendLine("Tool call: ${toolCall.name} (${toolCall.id})")
                append("Arguments: ${toolCall.arguments.clip(config.maxToolResultChars)}")
            },
            summary = "Tool call: ${toolCall.name} (${toolCall.id}) " +
                "Arguments: ${toolCall.arguments.collapseWhitespace().clip(MAX_SCRATCHPAD_SUMMARY_CHARS)}"
        )
        toolResult != null -> ScratchpadEntry(
            full = buildString {
                appendLine("Tool result: ${toolResult.name} (${toolResult.callId}) - ${toolResult.status()}")
                append(toolResult.content.trim().clip(config.maxToolResultChars))
                toolResult.visualAttachments.forEach { attachment ->
                    appendLine()
                    append(
                        "Visual: ref=${attachment.visualRef ?: attachment.assetId}; " +
                            "profile=${attachment.profile.storageValue}; mime=${attachment.mimeType}"
                    )
                }
            },
            summary = "Tool result: ${toolResult.name} (${toolResult.callId}) - ${toolResult.status()} " +
                buildString {
                    append(toolResult.content.collapseWhitespace())
                    toolResult.visualAttachments.forEach { attachment ->
                        append(" visual=")
                        append(attachment.visualRef ?: attachment.assetId)
                        append(" profile=")
                        append(attachment.profile.storageValue)
                    }
                }.clip(MAX_SCRATCHPAD_SUMMARY_CHARS)
        )
        else -> ScratchpadEntry(
            full = "${role.name}: ${content.trim().clip(config.maxToolResultChars)}",
            summary = "${role.name}: ${content.collapseWhitespace().clip(MAX_SCRATCHPAD_SUMMARY_CHARS)}"
        )
    }

    /**
     * One line naming every tool that has already run and how it ended.
     *
     * Cheap enough to keep unconditionally, and it is the one part of the scratchpad that survives eviction —
     * without it, a step whose result was dropped looks like a step that never happened, and the model calls
     * it again.
     */
    private fun List<ToolMessage>.progressDigest(): String? = mapNotNull(ToolMessage::toolResult)
        .joinToString(separator = ", ") { result -> "${result.name}=${result.status()}" }
        .takeIf { value -> value.isNotBlank() }
        ?.let { value -> "已完成: ${value.clip(MAX_PROGRESS_DIGEST_CHARS)}" }

    private data class ScratchpadEntry(val full: String, val summary: String)

    private fun ToolResult.status(): String = if (isError) "ERROR" else "OK"

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

        /**
         * Raised with [ToolLoopConfig.maxScratchpadChars] so a catalog-sized MCP result and the smaller steps
         * around it can occupy the same prompt. At 12,000 the prompt ceiling, not the scratchpad limit, was
         * what truncated a chain.
         */
        private const val DEFAULT_MAX_PROMPT_CHARS = 48_000
        private const val MAX_FALLBACK_TOOL_SUMMARY_CHARS = 120
        private const val MAX_SCRATCHPAD_SUMMARY_CHARS = 200
        private const val MAX_PROGRESS_DIGEST_CHARS = 400
        private const val ENTRY_SEPARATOR = "\n\n"
        private const val EARLIER_ENTRIES_OMITTED =
            "(更早的步骤已省略；上面的「已完成」列出了全部已运行的工具，不要重复调用。)"
    }
}

private fun String.collapseWhitespace(): String = trim().replace(SCRATCHPAD_WHITESPACE, " ")

private val SCRATCHPAD_WHITESPACE = Regex("\\s+")

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

/**
 * Which tool to reach for, as opposed to how to call it.
 *
 * `web_search` used to win by default in every ambiguous case: it is enabled out of the box, it ranks above
 * the device tools, and nothing in the prompt ever said another tool should be preferred. The result was a
 * model that searched the web for the device's own clock and for places it had no name for. These rules only
 * appear when `web_search` is actually active, since they are meaningless otherwise.
 *
 * "When to search at all" is deliberately not repeated here — the web_search guidance already carries it.
 */
internal fun toolSelectionPriorityRules(tools: Collection<ToolDefinition>): List<String> {
    val activeToolNames = tools.map(ToolDefinition::name).toSet()
    if (ToolDefinition.WebSearch.name !in activeToolNames) return emptyList()

    return buildList {
        if ((activeToolNames - WEB_FIRST_TOOL_NAMES).isNotEmpty()) {
            add("某项信息有专用工具（设备工具、MCP 工具）能直接提供时就用它，不要改用 web_search 代替。")
        }
        if (ToolDefinition.DeviceLocation.name in activeToolNames) {
            add(
                "请求依赖「我在哪 / 附近 / 本地」时，先调用 device_location，" +
                    "再把得到的位置写进 web_search 的 query；不要凭猜测的地名搜索。"
            )
        }
    }
}

/**
 * Tools that have no chance of answering in place of a web search: the web tools themselves, the scope
 * control, and the sticker pair, which expresses tone rather than retrieving anything.
 */
private val WEB_FIRST_TOOL_NAMES = setOf(
    ToolDefinition.WebSearch.name,
    ToolDefinition.FetchUrl.name,
    ToolDefinition.DiscoverTools.name,
    ToolDefinition.SearchStickers.name,
    ToolDefinition.SendSticker.name
)

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
