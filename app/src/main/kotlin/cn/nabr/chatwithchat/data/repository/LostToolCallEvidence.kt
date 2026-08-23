package cn.nabr.chatwithchat.data.repository

/**
 * Why a tool round produced no tool calls, and whether that is worth one more attempt.
 *
 * The native loops read an empty tool-call list as "the model chose to answer and the turn is over". That is
 * usually right and occasionally the worst available reading: a model that wrote its call out as text, or
 * was cut off partway through one, also produces nothing parsable, and ending the turn there leaves the user
 * with a half-finished message and a chat that has to be asked again from the top. Nothing in the round data
 * distinguished the two — `finish_reason` was parsed and never read, and the one component that recognises a
 * leaked envelope kept the fact to itself.
 *
 * Recovery is a retry with an instruction, not a rescue of the text. Parsing the leaked envelope back into a
 * real call was tried and removed, for a reason that has not changed: prose that merely looks like a call — a
 * model quoting documentation, or explaining a tool format — would become an actual tool execution, which is
 * far worse than losing a round. Asking the model to resend through the tool channel leaves the decision
 * with the model.
 */
internal data class LostToolCallEvidence(
    /** The round wrote a tool-call envelope into its text; see [LeakedToolCallMarkupFilter]. */
    val leakedMarkup: Boolean = false,
    /** The provider said it stopped in order to call tools, yet no call could be parsed out of the stream. */
    val providerClaimedToolCalls: Boolean = false,
    /** The provider ran out of output room, so a call may have been cut in half. */
    val outputTruncated: Boolean = false,
    /**
     * Whether anything reached the bubble.
     *
     * Decides what happens once the retries are spent. A round that leaked a call but also wrote a real
     * answer still ends the turn on that answer; a round that produced only the leak has nothing to show, and
     * is reported as a failure rather than saved as a successful empty turn.
     */
    val hadVisibleText: Boolean = false
) {
    val isLostToolCall: Boolean
        get() = leakedMarkup || providerClaimedToolCalls || outputTruncated
}

/**
 * One retry per turn, not more.
 *
 * A second attempt is cheap and fixes the case worth fixing — a model that slipped once and reissues the
 * call properly when told what went wrong. Past that, a provider is not going to start emitting tool calls
 * because it was asked a third time, and every attempt re-uploads the whole accumulated continuation.
 */
internal const val MAX_LOST_TOOL_CALL_RECOVERIES = 1

/** Reported when a round's tool call never arrived as one and the retry did not recover it. */
internal const val TOOL_CALL_CHANNEL_LOST = "tool_call_channel_lost"

/**
 * What the retry round is told.
 *
 * Names the failure concretely — the envelope shapes that actually leak — because the standing instruction
 * ("不要把调用写进正文") is already in the system prompt and was already ignored. A model that has just done
 * the thing it was told not to do needs to be told that it did it, not told the rule again.
 */
internal const val LOST_TOOL_CALL_RECOVERY_INSTRUCTION =
    "上一次响应没有产生可执行的工具调用：调用被写进了正文或标签（例如 <|…|tool_calls>、<invoke name=…>），" +
        "或者在中途被截断。写成文本的调用不会被执行，也不会有结果返回。" +
        "如果仍然需要该工具，请只通过 function 调用通道重新发起，正文里不要出现任何调用标记；" +
        "如果不再需要工具，请基于已有的工具结果直接给出最终回答。"
