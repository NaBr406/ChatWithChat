package dev.chungjungsoo.gptmobile.data.tool.provider

import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.common.Role
import dev.chungjungsoo.gptmobile.data.dto.google.request.GoogleFunctionDeclaration
import dev.chungjungsoo.gptmobile.data.dto.google.request.GoogleTool
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import dev.chungjungsoo.gptmobile.data.tool.ToolCall
import dev.chungjungsoo.gptmobile.data.tool.ToolDefinition
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopConfig
import dev.chungjungsoo.gptmobile.data.tool.ToolResult
import dev.chungjungsoo.gptmobile.data.tool.toolProtocolJson
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
                    parameters = definition.parameters.toGoogleSchema()
                )
            }
        )
    )

    fun toolCallsFromResponses(responses: List<GenerateContentResponse>): List<ToolCall> = responses
        .flatMap { response -> response.candidates.orEmpty() }
        .flatMap { candidate -> candidate.content.parts }
        .mapNotNull { part -> part.functionCall }
        .mapIndexed { index, functionCall ->
            ToolCall(
                id = functionCall.id?.takeIf { it.isNotBlank() } ?: "function_${index + 1}",
                name = functionCall.name,
                arguments = functionCall.args.toString().ifBlank { "{}" }
            )
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
    }

    private fun ToolDefinition.Parameters.toGoogleSchema(): JsonObject {
        val schema = toolProtocolJson.encodeToJsonElement(this).jsonObject
        return buildJsonObject {
            schema.forEach { (key, value) -> put(key, value) }
            put("additionalProperties", JsonPrimitive(false))
        }
    }
}

private fun String.clip(maxChars: Int): String {
    val boundedMax = maxChars.coerceAtLeast(0)
    if (length <= boundedMax) return this
    return take(boundedMax).trimEnd()
}
