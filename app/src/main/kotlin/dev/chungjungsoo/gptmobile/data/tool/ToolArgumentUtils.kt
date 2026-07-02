package dev.chungjungsoo.gptmobile.data.tool

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun ToolCall.stringArgument(argumentName: String): Result<String> = runCatching {
    val arguments = argumentsObject().getOrThrow()
    val value = arguments[argumentName]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()
    if (value.isBlank()) {
        throw IllegalArgumentException("${argumentName}_required")
    }
    value
}

internal fun String.clip(maxChars: Int): String {
    val boundedMax = maxChars.coerceAtLeast(0)
    if (length <= boundedMax) return this
    return take(boundedMax).trimEnd()
}
