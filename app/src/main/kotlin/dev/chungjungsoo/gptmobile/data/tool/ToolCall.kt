package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
) {
    fun argumentsObject(): Result<JsonObject> = runCatching {
        toolProtocolJson.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
    }

    companion object {
        fun parseFallbackCalls(rawText: String): Result<List<ToolCall>> = runCatching {
            val jsonText = rawText.extractJsonObject()
                ?: throw IllegalArgumentException("tool_call_json_not_found")
            val payload = toolProtocolJson.decodeFromString<FallbackToolCallPayload>(jsonText)
            if (payload.type != TOOL_CALLS_TYPE) {
                throw IllegalArgumentException("tool_call_type_expected")
            }
            payload.toolCalls
                .mapIndexed { index, call ->
                    ToolCall(
                        id = call.id?.trim()?.takeIf { it.isNotBlank() } ?: "call_${index + 1}",
                        name = call.name.trim(),
                        arguments = toolProtocolJson.encodeToString(call.arguments)
                    )
                }
                .filter { it.name.isNotBlank() }
        }
    }
}

@Serializable
private data class FallbackToolCallPayload(
    val type: String,
    @SerialName("tool_calls")
    val toolCalls: List<FallbackToolCall> = emptyList()
)

@Serializable
private data class FallbackToolCall(
    val id: String? = null,
    val name: String = "",
    val arguments: JsonObject = JsonObject(emptyMap())
)

private const val TOOL_CALLS_TYPE = "tool_calls"

private fun String.extractJsonObject(): String? {
    val start = indexOf('{')
    if (start < 0) return null

    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until length) {
        val char = this[index]
        when {
            escaped -> escaped = false
            char == '\\' && inString -> escaped = true
            char == '"' -> inString = !inString
            !inString && char == '{' -> depth += 1
            !inString && char == '}' -> {
                depth -= 1
                if (depth == 0) return substring(start, index + 1)
            }
        }
    }

    return null
}
