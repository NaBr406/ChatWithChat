package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.dto.openai.common.Role
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionFunctionTool
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionMessageFunctionCall
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionMessageToolCall
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionTool
import cn.nabr.chatwithchat.data.dto.openai.request.ChatMessage
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.tool.ToolArgumentStreamLimiter
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSchemaDialect
import cn.nabr.chatwithchat.data.tool.ToolSource
import cn.nabr.chatwithchat.data.tool.appendToolArgumentFragment
import cn.nabr.chatwithchat.data.tool.isOpenAIStrictCompatible
import cn.nabr.chatwithchat.data.tool.requireWithinToolArgumentLimit
import cn.nabr.chatwithchat.data.tool.toSchemaJson
import cn.nabr.chatwithchat.data.tool.toolProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

class OpenAIChatCompletionsToolAdapter {
    fun toChatCompletionTools(definitions: List<ToolDefinition>): List<ChatCompletionTool> = definitions.map { definition ->
        ChatCompletionTool(
            function = ChatCompletionFunctionTool(
                name = definition.name,
                description = definition.description,
                parameters = definition.parameters.toSchemaJson(ToolSchemaDialect.OPEN_AI),
                strict = definition.parameters.isOpenAIStrictCompatible()
            )
        )
    }

    fun toolCallsFromChunks(
        chunks: List<ChatCompletionChunk>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<ToolCall> {
        val accumulators = linkedMapOf<Int, ToolCallAccumulator>()
        val argumentLimiter = ToolArgumentStreamLimiter(
            maxArgumentChars = config.maxToolArgumentChars,
            maxCallIdentities = config.maxToolCallsPerRound
        )
        chunks.forEach { chunk ->
            chunk.choices.orEmpty().forEach { choice ->
                choice.delta.toolCalls.orEmpty().forEachIndexed { position, delta ->
                    argumentLimiter.register(choice.index to (delta.index ?: position))
                    val index = delta.index ?: position
                    val accumulator = accumulators.getOrPut(index) { ToolCallAccumulator(index = index) }
                    delta.id?.takeIf { it.isNotBlank() }?.let { accumulator.id = it }
                    delta.function?.name?.takeIf { it.isNotBlank() }?.let { accumulator.name = it }
                    delta.function?.arguments?.let { fragment ->
                        argumentLimiter.append(choice.index to (delta.index ?: position), fragment)
                        accumulator.arguments.appendToolArgumentFragment(fragment, config.maxToolArgumentChars)
                    }
                }
            }
        }

        return accumulators.values
            .sortedBy { it.index }
            .mapNotNull { accumulator -> accumulator.toToolCall(config.maxToolArgumentChars) }
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
                metadata = metadata,
                structuredContent = structuredContent,
                sources = sources
            )
        ),
        toolCallId = callId
    )

    private data class ToolCallAccumulator(
        val index: Int,
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toToolCall(maxArgumentChars: Int): ToolCall? {
            val toolName = name.trim()
            if (toolName.isBlank()) return null
            return ToolCall(
                id = id.trim().ifBlank { "call_${index + 1}" },
                name = toolName,
                arguments = arguments.toString()
                    .ifBlank { "{}" }
                    .requireWithinToolArgumentLimit(maxArgumentChars)
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
    val metadata: Map<String, String> = emptyMap(),
    @SerialName("structured_content")
    val structuredContent: JsonElement? = null,
    val sources: List<ToolSource> = emptyList()
)
