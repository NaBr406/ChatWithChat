package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionCallInputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionCallOutputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsePassthroughInputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseTool
import dev.chungjungsoo.gptmobile.data.dto.openai.response.FunctionCallArgumentsDeltaEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.FunctionCallArgumentsDoneEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputItemDoneEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.tool.ToolArgumentStreamLimiter
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.ToolSchemaDialect
import dev.chungjungsoo.gptmobile.data.tool.ToolSource
import dev.chungjungsoo.gptmobile.data.tool.isOpenAIStrictCompatible
import dev.chungjungsoo.gptmobile.data.tool.requireWithinToolArgumentLimit
import dev.chungjungsoo.gptmobile.data.tool.toSchemaJson
import dev.chungjungsoo.gptmobile.data.tool.toolProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

class OpenAIResponsesToolAdapter {
    fun toResponseTools(definitions: List<ToolDefinition>): List<ResponseTool> = definitions.map { definition ->
        ResponseTool(
            name = definition.name,
            description = definition.description,
            parameters = definition.parameters.toSchemaJson(ToolSchemaDialect.OPEN_AI),
            strict = definition.parameters.isOpenAIStrictCompatible()
        )
    }

    fun toolCallsFromEvents(
        events: List<ResponsesStreamEvent>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<ToolCall> {
        events.requireArgumentsWithinLimit(config)
        val callsFromArgumentEvents = events
            .asSequence()
            .filterIsInstance<FunctionCallArgumentsDoneEvent>()
            .mapNotNull { event ->
                event.toToolCall(config.maxToolArgumentChars)
            }
            .boundedDistinctById(config.maxToolCallsPerRound)

        if (callsFromArgumentEvents.isNotEmpty()) {
            return callsFromArgumentEvents
        }

        return events
            .asSequence()
            .filterIsInstance<OutputItemDoneEvent>()
            .mapNotNull { event -> event.item.toToolCall(config.maxToolArgumentChars) }
            .boundedDistinctById(config.maxToolCallsPerRound)
    }

    fun continuationInputItems(
        events: List<ResponsesStreamEvent>,
        calls: List<ToolCall>,
        results: List<ToolResult>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<ResponseInputItem> {
        val reasoningItems = events
            .filterIsInstance<OutputItemDoneEvent>()
            .map { it.item }
            .filter { it.type == REASONING_OUTPUT_TYPE }
            .map { item -> ResponsePassthroughInputItem(item.raw) }

        val callItems = calls.map { call -> call.toResponseFunctionCallInput(events) }
        val outputItems = results.map { result -> result.toResponseFunctionCallOutput(config) }

        return reasoningItems + callItems + outputItems
    }

    fun toolResultToResponseInput(
        result: ToolResult,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): ResponseFunctionCallOutputItem = result.toResponseFunctionCallOutput(config)

    private fun FunctionCallArgumentsDoneEvent.toToolCall(maxArgumentChars: Int): ToolCall? {
        val callId = callId.trim().ifBlank { itemId.trim() }
        val toolName = name.trim()
        if (callId.isBlank() || toolName.isBlank()) return null
        return ToolCall(
            id = callId,
            name = toolName,
            arguments = arguments.ifBlank { "{}" }.requireWithinToolArgumentLimit(maxArgumentChars)
        )
    }

    private fun OutputItem.toToolCall(maxArgumentChars: Int): ToolCall? {
        if (type != FUNCTION_CALL_OUTPUT_TYPE) return null
        val callId = callId?.trim()?.takeIf { it.isNotBlank() } ?: id.trim()
        val toolName = name?.trim().orEmpty()
        if (callId.isBlank() || toolName.isBlank()) return null
        return ToolCall(
            id = callId,
            name = toolName,
            arguments = (arguments?.takeIf { it.isNotBlank() } ?: "{}")
                .requireWithinToolArgumentLimit(maxArgumentChars)
        )
    }

    private fun List<ResponsesStreamEvent>.requireArgumentsWithinLimit(config: ToolLoopConfig) {
        val limiter = ToolArgumentStreamLimiter(
            maxArgumentChars = config.maxToolArgumentChars,
            maxCallIdentities = config.maxToolCallsPerRound
        )
        forEach { event ->
            when (event) {
                is FunctionCallArgumentsDeltaEvent -> limiter.append(event.outputIndex, event.delta)
                is FunctionCallArgumentsDoneEvent -> limiter.checkComplete(event.outputIndex, event.arguments)
                is OutputItemDoneEvent -> if (event.item.type == FUNCTION_CALL_OUTPUT_TYPE) {
                    limiter.checkComplete(event.outputIndex, event.item.arguments ?: "{}")
                }
                else -> {}
            }
        }
    }

    private fun ToolCall.toResponseFunctionCallInput(events: List<ResponsesStreamEvent>): ResponseFunctionCallInputItem {
        val matchingArgumentEvent = events
            .filterIsInstance<FunctionCallArgumentsDoneEvent>()
            .firstOrNull { event -> event.callId == id }
        val matchingOutputItem = events
            .filterIsInstance<OutputItemDoneEvent>()
            .map { it.item }
            .firstOrNull { item -> item.type == FUNCTION_CALL_OUTPUT_TYPE && item.callId == id }

        return ResponseFunctionCallInputItem(
            id = matchingOutputItem?.id?.takeIf { it.isNotBlank() } ?: matchingArgumentEvent?.itemId?.takeIf { it.isNotBlank() },
            callId = id,
            name = matchingOutputItem?.name?.takeIf { it.isNotBlank() } ?: matchingArgumentEvent?.name?.takeIf { it.isNotBlank() } ?: name,
            arguments = matchingOutputItem?.arguments?.takeIf { it.isNotBlank() } ?: matchingArgumentEvent?.arguments?.takeIf { it.isNotBlank() } ?: arguments
        )
    }

    private fun ToolResult.toResponseFunctionCallOutput(config: ToolLoopConfig): ResponseFunctionCallOutputItem = ResponseFunctionCallOutputItem(
        callId = callId,
        output = toolProtocolJson.encodeToString(
            OpenAIToolResultPayload(
                name = name,
                ok = !isError,
                content = content.clip(config.maxToolResultChars),
                metadata = metadata,
                structuredContent = structuredContent,
                sources = sources
            )
        )
    )

    private companion object {
        private const val FUNCTION_CALL_OUTPUT_TYPE = "function_call"
        private const val REASONING_OUTPUT_TYPE = "reasoning"
    }
}

private fun Sequence<ToolCall>.boundedDistinctById(maxCalls: Int): List<ToolCall> {
    val limit = maxCalls.coerceAtLeast(0)
    if (limit == 0) return emptyList()
    val calls = ArrayList<ToolCall>(minOf(limit, MAX_PREALLOCATED_TOOL_CALLS))
    val seenIds = mutableSetOf<String>()
    for (call in this) {
        if (seenIds.add(call.id)) calls += call
        if (calls.size >= limit) break
    }
    return calls
}

private fun String.clip(maxChars: Int): String {
    val boundedMax = maxChars.coerceAtLeast(0)
    if (length <= boundedMax) return this
    return take(boundedMax).trimEnd()
}

private const val MAX_PREALLOCATED_TOOL_CALLS = 16

@Serializable
private data class OpenAIToolResultPayload(
    val name: String,
    val ok: Boolean,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    @SerialName("structured_content")
    val structuredContent: JsonElement? = null,
    val sources: List<ToolSource> = emptyList()
)
