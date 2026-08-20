package cn.nabr.chatwithchat.data.tool

/**
 * Loop budgets.
 *
 * Sized for a multi-step agentic flow rather than a single lookup. A real MCP task — find a store, list a
 * menu, price a basket, place an order — is a chain of calls where each one needs the previous result, so
 * it consumes one round per step. The earlier three-round ceiling could answer a question but could never
 * finish a transaction.
 *
 * These are ceilings, not targets: a chat that needs one `current_datetime` call still costs one call. The
 * cost of raising them is paid only by conversations that actually go deep.
 */
data class ToolLoopConfig(
    val maxToolRounds: Int = 10,
    val maxToolCallsPerRound: Int = 4,
    val maxToolCallsPerChat: Int = 30,
    val toolTimeoutSeconds: Long = 20,
    val maxSearchResults: Int = 5,
    val maxFetchedPageChars: Int = 20_000,
    val maxToolArgumentChars: Int = 16_000,
    /**
     * Sized against a measured catalog response.
     *
     * A store menu from a real ordering server is 29,418 chars of text and takes no narrowing parameter —
     * no category, no keyword, no paging — so it arrives whole or not at all. At the previous 6,000 the
     * result was cut mid-JSON, which cost the model both the data and the ability to parse what survived.
     */
    val maxToolResultChars: Int = 32_000,
    /**
     * Must stay above [maxToolResultChars], and did not.
     *
     * At 16,000 against a 32,000-char result the scratchpad could not hold even one catalog response, so
     * overflow was the normal case and every earlier step of a chain was evicted by the newest result. The
     * eviction order is now recency-based rather than positional (see `ToolPromptBuilder.formatScratchpad`),
     * but the budget still has to leave room for one whole result plus the steps that led to it.
     */
    val maxScratchpadChars: Int = 40_000,
    /**
     * Enough for one catalog-sized result plus the smaller calls around it — a menu (29k), a store list, a
     * price quote, and an order confirmation all land in the same conversation.
     */
    val maxTotalToolResultChars: Int = 120_000,
    val fetchUrlBlockedDomains: Set<String> = emptySet(),
    val allowPrivateNetworkFetch: Boolean = false
) {
    companion object {
        val Default = ToolLoopConfig()
    }
}
