package dev.chungjungsoo.gptmobile.data.tool

class ToolArgumentsTooLargeException : IllegalArgumentException(TOOL_ARGUMENTS_TOO_LARGE)

class ToolCallIdentityLimitExceededException : IllegalArgumentException(TOOL_CALL_IDENTITY_LIMIT_EXCEEDED)

class ToolProtocolResponseTooLargeException : IllegalArgumentException(TOOL_PROTOCOL_RESPONSE_TOO_LARGE)

internal fun String.requireWithinToolArgumentLimit(maxChars: Int): String {
    if (length > maxChars.coerceAtLeast(0)) throw ToolArgumentsTooLargeException()
    return this
}

internal fun StringBuilder.appendToolArgumentFragment(
    fragment: String,
    maxChars: Int
) {
    val boundedMax = maxChars.coerceAtLeast(0)
    if (length > boundedMax || fragment.length > boundedMax - length) {
        throw ToolArgumentsTooLargeException()
    }
    append(fragment)
}

internal class ToolArgumentStreamLimiter(
    maxArgumentChars: Int,
    maxCallIdentities: Int
) {
    private val argumentLimit = maxArgumentChars.coerceAtLeast(0)
    private val identityLimit = maxCallIdentities.coerceAtLeast(0)
    private val streamedLengths = mutableMapOf<Any, Int>()
    private val identities = mutableSetOf<Any>()

    fun append(identity: Any, fragment: String) {
        register(identity)
        val currentLength = streamedLengths[identity] ?: 0
        if (currentLength > argumentLimit || fragment.length > argumentLimit - currentLength) {
            throw ToolArgumentsTooLargeException()
        }
        streamedLengths[identity] = currentLength + fragment.length
    }

    fun checkComplete(identity: Any, arguments: String) {
        register(identity)
        arguments.requireWithinToolArgumentLimit(argumentLimit)
    }

    fun register(identity: Any) {
        if (identity in identities) return
        if (identities.size >= identityLimit) throw ToolCallIdentityLimitExceededException()
        identities += identity
    }
}

internal fun ToolLoopConfig.maxToolProtocolResponseChars(): Int {
    val argumentPayload = maxToolArgumentChars.coerceAtLeast(0).toLong() *
        maxToolCallsPerRound.coerceAtLeast(0).toLong()
    return (argumentPayload + MAX_TOOL_PROTOCOL_OVERHEAD_CHARS)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun String.requireWithinToolProtocolResponseLimit(config: ToolLoopConfig): String {
    if (length > config.maxToolProtocolResponseChars()) throw ToolProtocolResponseTooLargeException()
    return this
}

internal fun String.toolLimitErrorCodeOrNull(): String? = TOOL_LIMIT_ERROR_CODES.firstOrNull { code ->
    contains(code)
}

internal fun StringBuilder.appendToolProtocolFragment(
    fragment: String,
    maxChars: Int
) {
    val boundedMax = maxChars.coerceAtLeast(0)
    if (length > boundedMax || fragment.length > boundedMax - length) {
        throw ToolProtocolResponseTooLargeException()
    }
    append(fragment)
}

internal fun Iterable<ToolCall>.boundedDistinctToolCalls(config: ToolLoopConfig): List<ToolCall> {
    val limit = config.maxToolCallsPerRound.coerceAtLeast(0)
    if (limit == 0) return emptyList()

    val selected = ArrayList<ToolCall>(minOf(limit, MAX_PREALLOCATED_TOOL_CALLS))
    val seen = mutableSetOf<ToolCallDedupeKey>()
    for (call in this) {
        val isNew = if (call.arguments.length > config.maxToolArgumentChars.coerceAtLeast(0)) {
            true
        } else {
            seen.add(ToolCallDedupeKey(call.name, call.arguments))
        }
        if (isNew) selected += call
        if (selected.size >= limit) break
    }
    return selected
}

private data class ToolCallDedupeKey(
    val name: String,
    val arguments: String
)

internal const val TOOL_ARGUMENTS_TOO_LARGE = "tool_arguments_too_large"
internal const val TOOL_CALL_IDENTITY_LIMIT_EXCEEDED = "tool_call_identity_limit_exceeded"
internal const val TOOL_PROTOCOL_RESPONSE_TOO_LARGE = "tool_protocol_response_too_large"

private const val MAX_TOOL_PROTOCOL_OVERHEAD_CHARS = 8_192L
private const val MAX_PREALLOCATED_TOOL_CALLS = 16
private val TOOL_LIMIT_ERROR_CODES = listOf(
    TOOL_ARGUMENTS_TOO_LARGE,
    TOOL_CALL_IDENTITY_LIMIT_EXCEEDED,
    TOOL_PROTOCOL_RESPONSE_TOO_LARGE
)
