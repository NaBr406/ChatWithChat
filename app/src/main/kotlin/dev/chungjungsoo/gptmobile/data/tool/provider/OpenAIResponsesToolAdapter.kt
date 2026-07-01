package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionCallInputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseFunctionCallOutputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsePassthroughInputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseTool
import dev.chungjungsoo.gptmobile.data.dto.openai.response.FunctionCallArgumentsDoneEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputItemDoneEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.toolProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class OpenAIResponsesToolAdapter {
    fun toResponseTools(definitions: List<ToolDefinition>): List<ResponseTool> = definitions.map { definition ->
        ResponseTool(
            name = definition.name,
            description = definition.description,
            parameters = definition.parameters.toOpenAISchema(),
            strict = true
        )
    }

    fun toolCallsFromEvents(events: List<ResponsesStreamEvent>): List<ToolCall> {
        val callsFromArgumentEvents = events
            .filterIsInstance<FunctionCallArgumentsDoneEvent>()
            .mapNotNull { event ->
                event.toToolCall()
            }

        if (callsFromArgumentEvents.isNotEmpty()) {
            return callsFromArgumentEvents.distinctBy { it.id }
        }

        return events
            .filterIsInstance<OutputItemDoneEvent>()
            .mapNotNull { event -> event.item.toToolCall() }
            .distinctBy { it.id }
    }

    fun continuationInputItems(
        events: List<ResponsesStreamEvent>,
        calls: List<ToolCall>,
        results: List<ToolResult>
    ): List<ResponseInputItem> {
        val reasoningItems = events
            .filterIsInstance<OutputItemDoneEvent>()
            .map { it.item }
            .filter { it.type == REASONING_OUTPUT_TYPE }
            .map { item -> ResponsePassthroughInputItem(item.raw) }

        val callItems = calls.map { call -> call.toResponseFunctionCallInput(events) }
        val outputItems = results.map { result -> result.toResponseFunctionCallOutput() }

        return reasoningItems + callItems + outputItems
    }

    fun toolResultToResponseInput(result: ToolResult): ResponseFunctionCallOutputItem = result.toResponseFunctionCallOutput()

    private fun FunctionCallArgumentsDoneEvent.toToolCall(): ToolCall? {
        val callId = callId.trim().ifBlank { itemId.trim() }
        val toolName = name.trim()
        if (callId.isBlank() || toolName.isBlank()) return null
        return ToolCall(
            id = callId,
            name = toolName,
            arguments = arguments.ifBlank { "{}" }
        )
    }

    private fun OutputItem.toToolCall(): ToolCall? {
        if (type != FUNCTION_CALL_OUTPUT_TYPE) return null
        val callId = callId?.trim()?.takeIf { it.isNotBlank() } ?: id.trim()
        val toolName = name?.trim().orEmpty()
        if (callId.isBlank() || toolName.isBlank()) return null
        return ToolCall(
            id = callId,
            name = toolName,
            arguments = arguments?.takeIf { it.isNotBlank() } ?: "{}"
        )
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

    private fun ToolResult.toResponseFunctionCallOutput(): ResponseFunctionCallOutputItem = ResponseFunctionCallOutputItem(
        callId = callId,
        output = toolProtocolJson.encodeToString(
            OpenAIToolResultPayload(
                name = name,
                ok = !isError,
                content = content,
                metadata = metadata
            )
        )
    )

    private fun ToolDefinition.Parameters.toOpenAISchema(): JsonObject {
        val schema = toolProtocolJson.encodeToJsonElement(this).jsonObject
        return buildJsonObject {
            schema.forEach { (key, value) -> put(key, value) }
            put("additionalProperties", JsonPrimitive(false))
        }
    }

    private companion object {
        private const val FUNCTION_CALL_OUTPUT_TYPE = "function_call"
        private const val REASONING_OUTPUT_TYPE = "reasoning"
    }
}

@Serializable
private data class OpenAIToolResultPayload(
    val name: String,
    val ok: Boolean,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)
