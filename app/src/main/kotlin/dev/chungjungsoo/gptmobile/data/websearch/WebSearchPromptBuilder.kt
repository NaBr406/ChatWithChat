package dev.chungjungsoo.gptmobile.data.websearch

class WebSearchPromptBuilder(
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
    private val maxSnippetChars: Int = DEFAULT_MAX_SNIPPET_CHARS,
    private val maxPromptChars: Int = DEFAULT_MAX_PROMPT_CHARS
) {
    fun build(results: List<WebSearchResult>): String? {
        val boundedResults = results
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .take(maxResults.coerceAtLeast(0))

        if (boundedResults.isEmpty()) return null

        val prompt = buildString {
            appendLine("Web search results:")
            appendLine("Use these results only when they are relevant. If you use them, cite the source URLs in the answer.")
            boundedResults.forEachIndexed { index, result ->
                appendLine()
                appendLine("${index + 1}. ${result.title.ifBlank { result.url }}")
                appendLine("URL: ${result.url}")
                appendLine("Source: ${result.source.ifBlank { "web" }}")
                result.publishedAt?.takeIf { it.isNotBlank() }?.let { publishedAt ->
                    appendLine("Published: $publishedAt")
                }
                result.snippet.trim().takeIf { it.isNotBlank() }?.let { snippet ->
                    appendLine("Snippet: ${snippet.clip(maxSnippetChars)}")
                }
            }
        }.trim()

        return prompt.clip(maxPromptChars).takeIf { it.isNotBlank() }
    }

    private fun String.clip(maxChars: Int): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (length <= boundedMax) return this
        return take(boundedMax).trimEnd()
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 5
        private const val DEFAULT_MAX_SNIPPET_CHARS = 500
        private const val DEFAULT_MAX_PROMPT_CHARS = 4_000
    }
}

