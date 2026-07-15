package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.database.entity.safeHttpUrlOrNull
import cn.nabr.chatwithchat.data.websearch.WebSearchRepository
import cn.nabr.chatwithchat.data.websearch.WebSearchResult

class WebSearchToolProvider(
    private val webSearchRepository: WebSearchRepository
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.WebSearch

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        category = ToolCategory.Web,
        defaultEnabled = true,
        isSensitive = false,
        presentationKey = "web_search",
        iconKey = "search"
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic
    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 3,
        maxCallsPerChat = 20,
        maxCallsPerRequestErrorKey = "max_search_queries_per_request",
        maxCallsPerChatErrorKey = "max_search_queries_per_chat"
    )

    override fun progressLabel(call: ToolCall): String = call.stringArgument("query")
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: definition.name

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
        val query = call.stringArgument("query").getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}")
        }
        val resultLimit = config.maxSearchResults.coerceAtLeast(0)
        val results = webSearchRepository.search(query, resultLimit).getOrElse { throwable ->
            val message = throwable.message ?: throwable::class.simpleName.orEmpty()
            return if (message.contains("web_search_backend_not_configured")) {
                call.errorResult("web_search_backend_not_configured")
            } else if (message.startsWith(WEB_SEARCH_UNRESPONSIVE_ENGINES_PREFIX)) {
                call.errorResult("web_search_failed:search backend unavailable: ${message.removePrefix(WEB_SEARCH_UNRESPONSIVE_ENGINES_PREFIX)}")
            } else {
                call.errorResult("web_search_failed:$message")
            }
        }
        val boundedResults = results
            .mapNotNull { result ->
                result.url.safeHttpUrlOrNull()?.let { safeUrl -> result.copy(url = safeUrl) }
            }
            .distinctBy { it.url }
            .take(resultLimit)

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = formatSearchResults(query, boundedResults),
            metadata = mapOf("result_count" to boundedResults.size.toString()),
            sources = boundedResults.map { result ->
                ToolSource.PublicUrl(
                    title = result.title.ifBlank { result.url },
                    url = result.url,
                    snippet = result.snippet.clip(MAX_SOURCE_SNIPPET_CHARS)
                )
            }
        )
    }

    private fun formatSearchResults(
        query: String,
        results: List<WebSearchResult>
    ): String {
        if (results.isEmpty()) {
            return "No web search results for: $query"
        }

        return buildString {
            appendLine("Web search results for: $query")
            results.forEachIndexed { index, result ->
                appendLine()
                appendLine("${index + 1}. ${result.title.ifBlank { result.url }}")
                appendLine("URL: ${result.url}")
                appendLine("Source: ${result.source.ifBlank { "web" }}")
                result.publishedAt?.takeIf { it.isNotBlank() }?.let { publishedAt ->
                    appendLine("Published: $publishedAt")
                }
                result.snippet.trim().takeIf { it.isNotBlank() }?.let { snippet ->
                    appendLine("Snippet: $snippet")
                }
            }
        }.trim()
    }
}

private const val MAX_SOURCE_SNIPPET_CHARS = 240
private const val WEB_SEARCH_UNRESPONSIVE_ENGINES_PREFIX = "web_search_no_results_unresponsive_engines:"
