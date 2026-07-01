package dev.chungjungsoo.gptmobile.data.tool

data class ToolLoopConfig(
    val maxToolRounds: Int = 1,
    val maxToolCallsPerRound: Int = 4,
    val toolTimeoutSeconds: Long = 20,
    val maxSearchResults: Int = 5,
    val maxFetchedPageChars: Int = 20_000,
    val maxToolResultChars: Int = 3_000,
    val maxScratchpadChars: Int = 8_000
) {
    companion object {
        val Default = ToolLoopConfig()
    }
}
