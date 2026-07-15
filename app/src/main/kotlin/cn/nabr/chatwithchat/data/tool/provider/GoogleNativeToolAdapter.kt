package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.dto.google.common.Content
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.google.common.Role
import cn.nabr.chatwithchat.data.dto.google.request.GoogleFunctionDeclaration
import cn.nabr.chatwithchat.data.dto.google.request.GoogleTool
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
import cn.nabr.chatwithchat.data.tool.ToolArgumentStreamLimiter
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolLoopConfig
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSchemaDialect
import cn.nabr.chatwithchat.data.tool.requireWithinToolArgumentLimit
import cn.nabr.chatwithchat.data.tool.toSchemaJson
import cn.nabr.chatwithchat.data.tool.toolProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class GoogleNativeToolAdapter {
    fun toGoogleTools(definitions: List<ToolDefinition>): List<GoogleTool> = listOf(
        GoogleTool(
            functionDeclarations = definitions.map { definition ->
                GoogleFunctionDeclaration(
                    name = definition.name,
                    description = definition.description,
                    parameters = definition.parameters.toSchemaJson(ToolSchemaDialect.GOOGLE)
                )
            }
        )
    )

    fun toolCallsFromResponses(
        responses: List<GenerateContentResponse>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<ToolCall> {
        val argumentLimiter = ToolArgumentStreamLimiter(
            maxArgumentChars = config.maxToolArgumentChars,
            maxCallIdentities = config.maxToolCallsPerRound
        )
        return responses
            .asSequence()
            .flatMap { response -> response.candidates.orEmpty().asSequence() }
            .flatMap { candidate -> candidate.content.parts.asSequence() }
            .mapNotNull { part -> part.functionCall }
            .mapIndexed { index, functionCall ->
                val arguments = functionCall.args.toString().ifBlank { "{}" }
                argumentLimiter.checkComplete(index, arguments)
                ToolCall(
                    id = functionCall.id?.takeIf { it.isNotBlank() } ?: "function_${index + 1}",
                    name = functionCall.name,
                    arguments = arguments.requireWithinToolArgumentLimit(config.maxToolArgumentChars)
                )
            }
            .toList()
    }

    fun continuationContents(
        calls: List<ToolCall>,
        results: List<ToolResult>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): List<Content> {
        if (calls.isEmpty()) return emptyList()

        val modelContent = Content(
            role = Role.MODEL,
            parts = calls.map { call ->
                Part.functionCall(
                    id = call.id,
                    name = call.name,
                    args = call.argsObject()
                )
            }
        )
        val userContent = Content(
            role = Role.USER,
            parts = results.map { result ->
                Part.functionResponse(
                    id = result.callId,
                    name = result.name,
                    response = result.toFunctionResponse(config)
                )
            }
        )
        return listOf(modelContent, userContent)
    }

    fun toolResultToFunctionResponse(
        result: ToolResult,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): JsonObject = result.toFunctionResponse(config)

    private fun ToolCall.argsObject(): JsonObject = runCatching {
        toolProtocolJson.parseToJsonElement(arguments).jsonObject
    }.getOrElse {
        buildJsonObject {}
    }

    private fun ToolResult.toFunctionResponse(config: ToolLoopConfig): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("ok", JsonPrimitive(!isError))
        put("content", JsonPrimitive(content.clip(config.maxToolResultChars)))
        put(
            "metadata",
            buildJsonObject {
                metadata.forEach { (key, value) ->
                    put(key, JsonPrimitive(value))
                }
            }
        )
        structuredContent?.let { value -> put("structured_content", value) }
        if (sources.isNotEmpty()) {
            put("sources", toolProtocolJson.encodeToJsonElement(sources))
        }
    }
}

private fun String.clip(maxChars: Int): String {
    val boundedMax = maxChars.coerceAtLeast(0)
    if (length <= boundedMax) return this
    return take(boundedMax).trimEnd()
}
