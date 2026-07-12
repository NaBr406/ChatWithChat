package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
        fun parseFallbackCalls(
            rawText: String,
            config: ToolLoopConfig = ToolLoopConfig.Default
        ): Result<List<ToolCall>> = runCatching {
            rawText.requireWithinToolProtocolResponseLimit(config)
            val jsonText = rawText.extractJsonObject()
                ?: throw IllegalArgumentException("tool_call_json_not_found")
            val payload = toolProtocolJson.parseToJsonElement(jsonText) as? JsonObject
                ?: throw IllegalArgumentException("tool_call_object_expected")
            parseFallbackToolCalls(payload, config)
        }
    }
}

internal fun parseFallbackToolCalls(
    payload: JsonObject,
    config: ToolLoopConfig
): List<ToolCall> {
    if (payload.stringValue("type") != TOOL_CALLS_TYPE) {
        throw IllegalArgumentException("tool_call_type_expected")
    }
    val rawCalls = payload["tool_calls"] ?: return emptyList()
    val calls = rawCalls as? JsonArray
        ?: throw IllegalArgumentException("tool_calls_array_expected")
    return calls
        .asSequence()
        .mapIndexedNotNull { index, element ->
            val call = element as? JsonObject
                ?: throw IllegalArgumentException("tool_call_object_expected")
            val name = call.stringValue("name").orEmpty().trim()
            if (name.isBlank()) return@mapIndexedNotNull null
            val arguments = when (val value = call["arguments"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> value
                else -> throw IllegalArgumentException("tool_arguments_object_expected")
            }.toString().requireWithinToolArgumentLimit(config.maxToolArgumentChars)
            ToolCall(
                id = call.stringValue("id")?.trim()?.takeIf { it.isNotBlank() } ?: "call_${index + 1}",
                name = name,
                arguments = arguments
            )
        }
        .asIterable()
        .boundedDistinctToolCalls(config)
}

private fun JsonObject.stringValue(key: String): String? {
    val value = this[key] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("tool_call_string_expected:$key")
    if (!primitive.isString) throw IllegalArgumentException("tool_call_string_expected:$key")
    return primitive.contentOrNull
}

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
