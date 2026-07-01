package dev.chungjungsoo.gptmobile.data.websearch

class SearchDecisionPromptBuilder(
    private val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
    private val maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS
) {
    fun build(
        latestUserMessage: String,
        recentContext: String?
    ): String = buildString {
        appendLine("Decide whether answering the latest user message requires live web search.")
        appendLine("Return only JSON with this exact shape:")
        appendLine("""{"shouldSearch":true,"queries":["query 1","query 2"],"reason":"short reason"}""")
        appendLine("Use at most $MAX_SEARCH_DECISION_QUERIES search queries.")
        appendLine("Choose shouldSearch=false for translation, math, coding syntax, writing help, or casual chat that can be answered without current external facts.")
        appendLine("Choose shouldSearch=true when the user explicitly asks to search, browse, look up, check online, use the web, or cite current sources.")
        appendLine("Choose shouldSearch=true for current events, changing facts, prices, schedules, laws, product availability, software versions, or requests that ask for latest/current/today.")
        appendLine("Choose shouldSearch=false for local clock time, local date, timezone, device state, or app settings unless the user asks for public web sources about them.")
        recentContext?.trim()?.takeIf { it.isNotBlank() }?.let { context ->
            appendLine()
            appendLine("Recent context:")
            appendLine(context.clip(maxContextChars))
        }
        appendLine()
        appendLine("Latest user message:")
        appendLine(latestUserMessage.trim().clip(maxMessageChars))
    }.trim()

    private fun String.clip(maxChars: Int): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (length <= boundedMax) return this
        return take(boundedMax).trimEnd()
    }

    companion object {
        private const val DEFAULT_MAX_MESSAGE_CHARS = 1_000
        private const val DEFAULT_MAX_CONTEXT_CHARS = 1_000
    }
}
