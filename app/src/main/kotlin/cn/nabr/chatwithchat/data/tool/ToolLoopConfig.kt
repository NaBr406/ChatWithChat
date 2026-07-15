package cn.nabr.chatwithchat.data.tool

data class ToolLoopConfig(
    val maxToolRounds: Int = 3,
    val maxToolCallsPerRound: Int = 4,
    val maxToolCallsPerChat: Int = 8,
    val toolTimeoutSeconds: Long = 20,
    val maxSearchResults: Int = 5,
    val maxFetchedPageChars: Int = 20_000,
    val maxToolArgumentChars: Int = 16_000,
    val maxToolResultChars: Int = 3_000,
    val maxScratchpadChars: Int = 8_000,
    val maxTotalToolResultChars: Int = 8_000,
    val fetchUrlBlockedDomains: Set<String> = emptySet(),
    val allowPrivateNetworkFetch: Boolean = false
) {
    companion object {
        val Default = ToolLoopConfig()
    }
}
