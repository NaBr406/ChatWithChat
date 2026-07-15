package cn.nabr.chatwithchat.data.tool

import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class ToolPromptBuilder(
    private val maxToolDefinitionChars: Int = DEFAULT_MAX_TOOL_DEFINITION_CHARS,
    private val maxPromptChars: Int = DEFAULT_MAX_PROMPT_CHARS
) {
    fun buildJsonFallbackPrompt(
        tools: List<ToolDefinition> = emptyList(),
        scratchpad: List<ToolMessage> = emptyList(),
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): String {
        val prompt = buildString {
            appendLine("You may call tools before answering.")
            appendLine("Return only valid JSON. Do not use markdown fences or add extra text.")
            appendLine()
            appendLine("If no tool is needed, return:")
            appendLine("""{"type":"final_answer","content":"answer text"}""")
            appendLine()
            appendLine("If tools are needed, return:")
            appendLine(exampleToolCallJson(tools))
            appendLine()
            appendLine("Rules:")
            appendLine("- Use only the listed tool names.")
            appendLine("- Use at most ${config.maxToolCallsPerRound.coerceAtLeast(0)} tool calls in one response.")
            appendLine("- Use at most ${config.maxToolRounds.coerceAtLeast(0)} tool rounds before returning final_answer.")
            appendLine("- Keep arguments concise and match each tool parameter schema.")
            appendLine("- If a tool result is an error with tool_permission_denied, tell the user which Android permission is missing and ask them to enable it before retrying.")
            if (tools.any { tool -> tool.name == ToolDefinition.WebSearch.name }) {
                appendLine("- For web_search, rewrite the user's request into a search-engine query with likely entity, topic/category, timeframe, and geography/source scope; do not merely copy the user's wording.")
                appendLine("- Prefer official, primary, or local-language source terms for factual data such as weather, laws, finance, health, releases, and schedules.")
                appendLine("- Resolve relative dates such as today, yesterday, latest, or current into concrete dates or years when the conversation/runtime context provides them.")
                appendLine("- For broad search requests, choose sensible default scopes and complementary queries instead of asking a clarifying question first.")
                appendLine("- Do not call web_search for the user's local date, time, timezone, device state, or app settings.")
            }
            if (tools.any { tool -> tool.name == ToolDefinition.FetchUrl.name }) {
                appendLine("- Use fetch_url only for pages that are clearly worth reading.")
            }
            appendLine("- Prefer final_answer when the existing conversation is enough.")
            appendLine()
            appendLine("Available tools:")
            appendLine(formatToolDefinitionsWithinBudget(tools))
            formatScratchpad(scratchpad, config)?.let { scratchpadText ->
                appendLine()
                appendLine("Tool scratchpad:")
                appendLine(scratchpadText)
            }
        }.trim()

        return prompt.clip(maxPromptChars)
    }

    fun formatToolDefinitions(tools: List<ToolDefinition>): String = tools.joinToString(separator = "\n\n") { tool ->
        tool.toPromptText()
    }.trim()

    private fun exampleToolCallJson(tools: List<ToolDefinition>): String {
        val tool = tools.firstOrNull()
            ?: return """{"type":"tool_calls","tool_calls":[]}"""
        val arguments = when (tool.name) {
            ToolDefinition.WebSearch.name -> buildJsonObject {
                put("query", JsonPrimitive("search query"))
            }
            ToolDefinition.FetchUrl.name -> buildJsonObject {
                put("url", JsonPrimitive("https://example.com/page"))
            }
            else -> tool.parameters.exampleArguments()
        }
        return buildJsonObject {
            put("type", JsonPrimitive("tool_calls"))
            put(
                "tool_calls",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive("call_1"))
                            put("name", JsonPrimitive(tool.name))
                            put("arguments", arguments)
                        }
                    )
                }
            )
        }.toString()
    }

    private fun ToolDefinition.Parameters.exampleArguments(): JsonObject = buildJsonObject {
        required.distinct().forEach { name ->
            properties[name]?.let { schema -> put(name, schema.exampleValue()) }
        }
    }

    private fun ToolDefinition.Parameter.exampleValue(): JsonElement = enumValues
        .firstOrNull()
        ?.let(::JsonPrimitive)
        ?: when (type) {
            JSON_SCHEMA_OBJECT -> buildJsonObject {
                required.distinct().forEach { name ->
                    properties[name]?.let { schema -> put(name, schema.exampleValue()) }
                }
            }
            JSON_SCHEMA_ARRAY -> buildJsonArray {
                items?.let { schema -> add(schema.exampleValue()) }
            }
            JSON_SCHEMA_INTEGER -> JsonPrimitive(integerExample())
            JSON_SCHEMA_NUMBER -> JsonPrimitive(numberExample())
            JSON_SCHEMA_BOOLEAN -> JsonPrimitive(false)
            JSON_SCHEMA_STRING -> JsonPrimitive(stringExample())
            else -> JsonPrimitive("value")
        }

    private fun ToolDefinition.Parameter.integerExample(): Long {
        val lowerBound = minimum
            ?.takeIf { value -> value.isFinite() }
            ?.let(::ceil)
            ?.toLong()
            ?: 0L
        val upperBound = maximum
            ?.takeIf { value -> value.isFinite() }
            ?.let(::floor)
            ?.toLong()
        return upperBound?.let { maximum -> lowerBound.coerceAtMost(maximum) } ?: lowerBound
    }

    private fun ToolDefinition.Parameter.numberExample(): Double {
        val lowerBound = minimum?.takeIf { value -> value.isFinite() } ?: 0.0
        val upperBound = maximum?.takeIf { value -> value.isFinite() }
        return upperBound?.let { maximum -> lowerBound.coerceAtMost(maximum) } ?: lowerBound
    }

    private fun ToolDefinition.Parameter.stringExample(): String {
        val base = when (format) {
            "date-time" -> "2026-01-01T00:00:00Z"
            "date" -> "2026-01-01"
            "time" -> "00:00:00Z"
            "email" -> "user@example.com"
            "hostname" -> "example.com"
            "ipv4" -> "192.0.2.1"
            "ipv6" -> "2001:db8::1"
            "uuid" -> "00000000-0000-4000-8000-000000000000"
            "uri", "url" -> "https://example.com"
            else -> "value"
        }
        val minimumLength = minLength?.coerceAtLeast(0) ?: 0
        val padded = if (base.length < minimumLength) {
            base + "x".repeat(minimumLength - base.length)
        } else {
            base
        }
        return maxLength?.coerceAtLeast(0)?.let { maximumLength -> padded.take(maximumLength) } ?: padded
    }

    private fun formatToolDefinitionsWithinBudget(tools: List<ToolDefinition>): String {
        val boundedMax = maxToolDefinitionChars.coerceAtLeast(0)
        val result = StringBuilder()
        tools.forEach { tool ->
            val block = tool.toPromptText()
            val separator = if (result.isEmpty()) "" else "\n\n"
            if (result.length + separator.length + block.length <= boundedMax) {
                result.append(separator)
                result.append(block)
            }
        }
        return result.toString()
    }

    fun formatToolResults(
        results: List<ToolResult>,
        config: ToolLoopConfig = ToolLoopConfig.Default
    ): String? {
        if (results.isEmpty()) return null

        val text = buildString {
            appendLine("Tool results:")
            results.forEachIndexed { index, result ->
                appendLine()
                appendLine("${index + 1}. ${result.name} (${result.callId}) - ${if (result.isError) "ERROR" else "OK"}")
                if (result.metadata.isNotEmpty()) {
                    result.metadata.toSortedMap().forEach { (key, value) ->
                        appendLine("Metadata $key: $value")
                    }
                }
                appendLine("Content:")
                appendLine(result.content.trim().clip(config.maxToolResultChars))
                result.structuredContent?.let { structuredContent ->
                    appendLine("Structured content:")
                    appendLine(toolProtocolJson.encodeToString(structuredContent))
                }
                result.sources.forEach { source ->
                    appendLine("Source: ${toolProtocolJson.encodeToString(source)}")
                }
            }
        }.trim()

        return text.clip(config.toolResultInjectionLimit()).takeIf { it.isNotBlank() }
    }

    private fun formatScratchpad(
        scratchpad: List<ToolMessage>,
        config: ToolLoopConfig
    ): String? {
        if (scratchpad.isEmpty()) return null

        val text = buildString {
            scratchpad.forEachIndexed { index, message ->
                appendLine("${index + 1}. ${message.role.name}:")
                when {
                    message.toolCall != null -> {
                        appendLine("Tool call: ${message.toolCall.name} (${message.toolCall.id})")
                        appendLine("Arguments: ${message.toolCall.arguments.clip(config.maxToolResultChars)}")
                    }
                    message.toolResult != null -> {
                        appendLine("Tool result: ${message.toolResult.name} (${message.toolResult.callId})")
                        appendLine("Status: ${if (message.toolResult.isError) "ERROR" else "OK"}")
                        appendLine(message.toolResult.content.trim().clip(config.maxToolResultChars))
                    }
                    else -> appendLine(message.content.trim().clip(config.maxToolResultChars))
                }
            }
        }.trim()

        return text.clip(config.toolResultInjectionLimit()).takeIf { it.isNotBlank() }
    }

    private fun ToolLoopConfig.toolResultInjectionLimit(): Int = minOf(
        maxScratchpadChars,
        maxTotalToolResultChars
    ).coerceAtLeast(0)

    private fun String.clip(maxChars: Int): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (length <= boundedMax) return this
        return take(boundedMax).trimEnd()
    }

    companion object {
        private const val DEFAULT_MAX_TOOL_DEFINITION_CHARS = 4_000
        private const val DEFAULT_MAX_PROMPT_CHARS = 12_000
    }
}
