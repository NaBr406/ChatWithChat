package cn.nabr.chatwithchat.data.tool

class ToolArgumentsTooLargeException : IllegalArgumentException(TOOL_ARGUMENTS_TOO_LARGE)

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
    private val overflowIdentities = mutableSetOf<Any>()

    /**
     * Distinct tool calls this round asked for beyond [maxCallIdentities].
     *
     * Saturates at [MAX_TRACKED_OVERFLOW_IDENTITIES]: past that the exact number tells the model nothing
     * it can act on, and tracking every identity of a runaway response is the leak the cap exists to avoid.
     */
    val droppedIdentityCount: Int
        get() = overflowIdentities.size

    /**
     * True when [identity] fits within the per-round cap, so its payload should be accumulated.
     *
     * Overflow used to throw, which aborted the provider stream mid-round and surfaced a bare
     * `tool_call_identity_limit_exceeded` to the user — a model that asked to read five files in one round
     * lost the whole turn, having never been told a limit existed. Overflow is now skipped and counted so
     * the round completes on the calls that fit and the model can be told what to resubmit.
     */
    fun register(identity: Any): Boolean {
        if (identity in identities) return true
        if (identities.size >= identityLimit) {
            if (overflowIdentities.size < MAX_TRACKED_OVERFLOW_IDENTITIES) {
                overflowIdentities += identity
            }
            return false
        }
        identities += identity
        return true
    }

    fun append(identity: Any, fragment: String) {
        if (!register(identity)) return
        val currentLength = streamedLengths[identity] ?: 0
        if (currentLength > argumentLimit || fragment.length > argumentLimit - currentLength) {
            throw ToolArgumentsTooLargeException()
        }
        streamedLengths[identity] = currentLength + fragment.length
    }

    fun checkComplete(identity: Any, arguments: String) {
        if (!register(identity)) return
        arguments.requireWithinToolArgumentLimit(argumentLimit)
    }
}

/**
 * Ceiling on a whole JSON-fallback protocol response.
 *
 * Clamped rather than left as `argumentChars * callsPerRound`, because that product made a payload defence
 * ride on the per-round call cap: raising the cap from 4 to 16 would have quadrupled the accepted response
 * body from 72 KB to 264 KB as a side effect nobody asked for.
 */
internal fun ToolLoopConfig.maxToolProtocolResponseChars(): Int {
    val argumentPayload = maxToolArgumentChars.coerceAtLeast(0).toLong() *
        maxToolCallsPerRound.coerceAtLeast(0).toLong()
    return (argumentPayload + MAX_TOOL_PROTOCOL_OVERHEAD_CHARS)
        .coerceAtMost(MAX_TOOL_PROTOCOL_RESPONSE_CHARS)
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
internal const val TOOL_PROTOCOL_RESPONSE_TOO_LARGE = "tool_protocol_response_too_large"

private const val MAX_TOOL_PROTOCOL_OVERHEAD_CHARS = 8_192L

/** Matches what the previous `16_000 * 4 + 8_192` product allowed, now stated instead of derived. */
private const val MAX_TOOL_PROTOCOL_RESPONSE_CHARS = 72_192L

private const val MAX_PREALLOCATED_TOOL_CALLS = 16

/** How many overflowing tool-call identities one round records before it stops counting. */
private const val MAX_TRACKED_OVERFLOW_IDENTITIES = 256

private val TOOL_LIMIT_ERROR_CODES = listOf(
    TOOL_ARGUMENTS_TOO_LARGE,
    TOOL_PROTOCOL_RESPONSE_TOO_LARGE
)
