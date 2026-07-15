package cn.nabr.chatwithchat.data.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsonToolCallParser {
    fun parse(
        rawText: String,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): Result<JsonToolModelOutput> = runCatching {
        rawText.requireWithinToolProtocolResponseLimit(config)
        val jsonText = rawText.extractJsonObject()
            ?: throw IllegalArgumentException("tool_response_json_not_found")
        val payload = toolProtocolJson.parseToJsonElement(jsonText).jsonObject
        when (payload["type"]?.jsonPrimitive?.contentOrNull) {
            FINAL_ANSWER_TYPE -> JsonToolModelOutput.FinalAnswer(
                content = payload["content"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            )
            TOOL_CALLS_TYPE -> JsonToolModelOutput.ToolCalls(
                calls = parseFallbackToolCalls(payload, config)
            )
            else -> throw IllegalArgumentException("tool_response_type_unknown")
        }
    }

    fun hasToolCallIntent(rawText: String): Boolean {
        val parsedPayload = rawText.extractJsonObject()
            ?.let { jsonText ->
                runCatching {
                    toolProtocolJson.parseToJsonElement(jsonText)
                        .jsonObject
                }.getOrNull()
            }
        if (parsedPayload != null) {
            return (parsedPayload["tool_calls"] as? JsonArray)?.isNotEmpty() == true
        }
        return NON_EMPTY_TOOL_CALLS_PATTERN.containsMatchIn(rawText)
    }

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

    private companion object {
        private const val FINAL_ANSWER_TYPE = "final_answer"
        private const val TOOL_CALLS_TYPE = "tool_calls"
        private val NON_EMPTY_TOOL_CALLS_PATTERN = Regex("""(?<!\\)"tool_calls"\s*:\s*\[\s*[^\]\s]""")
    }
}

sealed class JsonToolModelOutput {
    data class FinalAnswer(
        val content: String
    ) : JsonToolModelOutput()

    data class ToolCalls(
        val calls: List<ToolCall>
    ) : JsonToolModelOutput()
}
