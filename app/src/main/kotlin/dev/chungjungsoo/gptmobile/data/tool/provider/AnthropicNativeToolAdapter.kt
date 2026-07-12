package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolUseContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.AnthropicTool
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlockType
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentDeltaResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentStartResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.tool.ToolArgumentStreamLimiter
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.ToolSchemaDialect
import dev.chungjungsoo.gptmobile.data.tool.ToolSource
import dev.chungjungsoo.gptmobile.data.tool.appendToolArgumentFragment
import dev.chungjungsoo.gptmobile.data.tool.requireWithinToolArgumentLimit
import dev.chungjungsoo.gptmobile.data.tool.toSchemaJson
import dev.chungjungsoo.gptmobile.data.tool.toolProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class AnthropicNativeToolAdapter {
    fun toAnthropicTools(definitions: List<ToolDefinition>): List<AnthropicTool> = definitions.map { definition ->
        AnthropicTool(
            name = definition.name,
            description = definition.description,
            inputSchema = definition.parameters.toSchemaJson(ToolSchemaDialect.ANTHROPIC)
        )
    }

    fun toolCallsFromChunks(
        chunks: List<MessageResponseChunk>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<ToolCall> {
        val accumulators = linkedMapOf<Int, ToolCallAccumulator>()
        val argumentLimiter = ToolArgumentStreamLimiter(
            maxArgumentChars = config.maxToolArgumentChars,
            maxCallIdentities = config.maxToolCallsPerRound
        )
        chunks.forEach { chunk ->
            when (chunk) {
                is ContentStartResponseChunk -> {
                    if (chunk.contentBlock.type == ContentBlockType.TOOL_USE) {
                        argumentLimiter.register(chunk.index)
                        val accumulator = accumulators.getOrPut(chunk.index) { ToolCallAccumulator(index = chunk.index) }
                        chunk.contentBlock.id?.takeIf { it.isNotBlank() }?.let { accumulator.id = it }
                        chunk.contentBlock.name?.takeIf { it.isNotBlank() }?.let { accumulator.name = it }
                        chunk.contentBlock.input?.let { input ->
                            argumentLimiter.checkComplete(chunk.index, input.toString())
                            accumulator.input = input
                        }
                    }
                }
                is ContentDeltaResponseChunk -> {
                    if (chunk.delta.type == ContentBlockType.INPUT_JSON_DELTA) {
                        val accumulator = accumulators.getOrPut(chunk.index) { ToolCallAccumulator(index = chunk.index) }
                        chunk.delta.partialJson?.let { fragment ->
                            argumentLimiter.append(chunk.index, fragment)
                            accumulator.partialJson.appendToolArgumentFragment(fragment, config.maxToolArgumentChars)
                        }
                    }
                }
                else -> {}
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
    ): List<InputMessage> {
        if (calls.isEmpty()) return emptyList()

        val assistantMessage = InputMessage(
            role = MessageRole.ASSISTANT,
            content = calls.map { call ->
                ToolUseContent(
                    id = call.id,
                    name = call.name,
                    input = call.inputObject()
                )
            }
        )
        val resultMessage = InputMessage(
            role = MessageRole.USER,
            content = results.map { result -> result.toToolResultContent(config) }
        )
        return listOf(assistantMessage, resultMessage)
    }

    fun toolResultToMessageContent(
        result: ToolResult,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): ToolResultContent = result.toToolResultContent(config)

    private fun ToolCall.inputObject(): JsonObject = runCatching {
        toolProtocolJson.parseToJsonElement(arguments).jsonObject
    }.getOrElse {
        buildJsonObject {}
    }

    private fun ToolResult.toToolResultContent(config: ToolLoopConfig): ToolResultContent = ToolResultContent(
        toolUseId = callId,
        content = toolProtocolJson.encodeToString(
            AnthropicToolResultPayload(
                name = name,
                ok = !isError,
                content = content.clip(config.maxToolResultChars),
                metadata = metadata,
                structuredContent = structuredContent,
                sources = sources
            )
        ),
        isError = isError.takeIf { it }
    )

    private data class ToolCallAccumulator(
        val index: Int,
        var id: String = "",
        var name: String = "",
        var input: JsonObject? = null,
        val partialJson: StringBuilder = StringBuilder()
    ) {
        fun toToolCall(maxArgumentChars: Int): ToolCall? {
            val toolName = name.trim()
            if (toolName.isBlank()) return null
            val arguments = partialJson.toString()
                .ifBlank { input?.toString().orEmpty() }
                .ifBlank { "{}" }
                .requireWithinToolArgumentLimit(maxArgumentChars)
            return ToolCall(
                id = id.trim().ifBlank { "toolu_${index + 1}" },
                name = toolName,
                arguments = arguments
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
private data class AnthropicToolResultPayload(
    val name: String,
    val ok: Boolean,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    @SerialName("structured_content")
    val structuredContent: JsonElement? = null,
    val sources: List<ToolSource> = emptyList()
)
