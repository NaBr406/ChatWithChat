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
    /**
     * Raised from 4, which was narrow enough to be the limit a real task hit first.
     *
     * Reading six files out of a repository, or pricing four items, is one step of work that happens to be
     * six independent calls — at 4 the model either lost two of them or spent an extra round it did not need,
     * and rounds are the scarcer budget. Overflow used to abort the provider stream mid-round and show the
     * user a bare `tool_call_identity_limit_exceeded`; it now truncates and tells the model what to
     * resubmit (see `ToolArgumentStreamLimiter.register`). Total volume is still held by
     * [maxToolCallsPerChat], so this only controls how much of it may be spent at once.
     */
    val maxToolCallsPerRound: Int = 16,
    /**
     * Raised from 30, which was the limit almost every deep task actually hit.
     *
     * Sized against [maxToolRounds] × [maxToolCallsPerRound] rather than picked on its own. At 30 against a
     * 16-per-round cap, a model that batched freely spent the whole request's allowance in five rounds and
     * left half the round budget unusable — the two ceilings were 160 apart and the smaller one always won.
     * 60 lets a repository walk or a multi-step order finish while still stopping a runaway well short of
     * the 160 the rounds would otherwise permit.
     *
     * [maxTotalToolResultChars] had to move with it. Result volume, not call count, is what a raise really
     * costs, and a call whose result no longer fits comes back empty.
     */
    val maxToolCallsPerChat: Int = 60,
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
     * Raised with [maxToolCallsPerChat], but deliberately not in proportion.
     *
     * This is the budget a raise actually spends. The native loops accumulate every round's tool results in
     * the continuation and re-send the whole thing on the next request, so a doubled call count multiplies
     * upload rather than adding to it: 120,000 chars spread over five rounds is already about 600,000 chars
     * on the wire. Doubling the calls while only raising this by two thirds keeps a deep task from running
     * dry without turning one message into megabytes.
     *
     * Still enough for one catalog-sized result plus the smaller calls around it — a menu (29k), a store
     * list, a price quote and an order confirmation — with room now for a repository walk on top.
     *
     * Note the JSON fallback path does not see the raise: its injection limit is
     * `min(maxScratchpadChars, maxTotalToolResultChars)`, so [maxScratchpadChars] still clamps it to 40,000.
     */
    val maxTotalToolResultChars: Int = 200_000,
    val fetchUrlBlockedDomains: Set<String> = emptySet(),
    val allowPrivateNetworkFetch: Boolean = false
) {
    companion object {
        val Default = ToolLoopConfig()
    }
}
