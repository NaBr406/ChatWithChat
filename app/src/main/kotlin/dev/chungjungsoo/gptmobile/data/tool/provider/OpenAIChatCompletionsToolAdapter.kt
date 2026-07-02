package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionFunctionTool
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionMessageFunctionCall
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionMessageToolCall
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionTool
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.toolProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class OpenAIChatCompletionsToolAdapter {
    fun toChatCompletionTools(definitions: List<ToolDefinition>): List<ChatCompletionTool> = definitions.map { definition ->
        ChatCompletionTool(
            function = ChatCompletionFunctionTool(
                name = definition.name,
                description = definition.description,
                parameters = definition.parameters.toOpenAISchema(),
                strict = true
            )
        )
    }

    fun toolCallsFromChunks(chunks: List<ChatCompletionChunk>): List<ToolCall> {
        val accumulators = linkedMapOf<Int, ToolCallAccumulator>()
        chunks.forEach { chunk ->
            chunk.choices.orEmpty().forEach { choice ->
                choice.delta.toolCalls.orEmpty().forEach { delta ->
                    val index = delta.index ?: accumulators.size
                    val accumulator = accumulators.getOrPut(index) { ToolCallAccumulator(index = index) }
                    delta.id?.takeIf { it.isNotBlank() }?.let { accumulator.id = it }
                    delta.function?.name?.takeIf { it.isNotBlank() }?.let { accumulator.name = it }
                    delta.function?.arguments?.let { accumulator.arguments.append(it) }
                }
            }
        }

        return accumulators.values
            .sortedBy { it.index }
            .mapNotNull { accumulator -> accumulator.toToolCall() }
    }

    fun continuationMessages(
        calls: List<ToolCall>,
        results: List<ToolResult>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<ChatMessage> {
        if (calls.isEmpty()) return emptyList()

        val assistantMessage = ChatMessage(
            role = Role.ASSISTANT,
            toolCalls = calls.map { call -> call.toChatCompletionMessageToolCall() }
        )
        val resultMessages = results.map { result -> result.toToolMessage(config) }
        return listOf(assistantMessage) + resultMessages
    }

    fun toolResultToChatMessage(
        result: ToolResult,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): ChatMessage = result.toToolMessage(config)

    private fun ToolCall.toChatCompletionMessageToolCall(): ChatCompletionMessageToolCall = ChatCompletionMessageToolCall(
        id = id,
        function = ChatCompletionMessageFunctionCall(
            name = name,
            arguments = arguments.ifBlank { "{}" }
        )
    )

    private fun ToolResult.toToolMessage(config: ToolLoopConfig): ChatMessage = ChatMessage(
        role = Role.TOOL,
        contentText = toolProtocolJson.encodeToString(
            OpenAIChatToolResultPayload(
                name = name,
                ok = !isError,
                content = content.clip(config.maxToolResultChars),
                metadata = metadata
            )
        ),
        toolCallId = callId
    )

    private fun ToolDefinition.Parameters.toOpenAISchema(): JsonObject {
        val schema = toolProtocolJson.encodeToJsonElement(this).jsonObject
        return buildJsonObject {
            schema.forEach { (key, value) -> put(key, value) }
            put("additionalProperties", JsonPrimitive(false))
        }
    }

    private data class ToolCallAccumulator(
        val index: Int,
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toToolCall(): ToolCall? {
            val toolName = name.trim()
            if (toolName.isBlank()) return null
            return ToolCall(
                id = id.trim().ifBlank { "call_${index + 1}" },
                name = toolName,
                arguments = arguments.toString().ifBlank { "{}" }
            )
        }
    }
}

private fun String.clip(maxChars: Int): String {
    val boundedMax = maxChars.coerceAtLeast(0)
    if (length <= boundedMax) return this
    return take(boundedMax).trimEnd()
}

@Serializable
private data class OpenAIChatToolResultPayload(
    val name: String,
    val ok: Boolean,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)
