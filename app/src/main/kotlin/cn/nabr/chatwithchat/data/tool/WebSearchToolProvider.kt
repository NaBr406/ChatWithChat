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

    /**
     * Resident, and it has to be.
     *
     * As an on-demand tool its visibility depended on a literal tag hit, which is why `android`, `sdk` and
     * `target` were once in this set: they existed so that "current Android target SDK" could reach the tool
     * at all. That is backwards — the general-purpose retrieval tool is exactly the one whose availability
     * should not hinge on guessing the vocabulary of the question, and tags bought to satisfy one phrasing
     * also made web_search the top match for unrelated platform questions a local tool answered.
     *
     * Being visible is not being preferred. What keeps the model off the web now is the ranking guidance in
     * `toolSelectionPriorityRules` plus the pre-flight shortcut standing down for tools that can answer
     * better, not an intent matcher hiding the schema. Tags below only order an overflowing resident set.
     */
    override val discoveryMetadata: ToolDiscoveryMetadata = ToolDiscoveryMetadata(
        exposure = ToolExposure.Resident,
        intentTags = setOf("web", "search", "latest", "current", "today", "yesterday", "news", "happened", "research", "internet", "网页", "搜索", "最新", "当前", "今天", "昨天", "新闻", "发生", "查一下"),
        priority = 30
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
            return when {
                message.contains("web_search_backend_not_configured") ->
                    call.errorResult("web_search_backend_not_configured")
                message.startsWith(WEB_SEARCH_UNRESPONSIVE_ENGINES_PREFIX) ->
                    call.errorResult("web_search_failed:search backend unavailable: ${message.removePrefix(WEB_SEARCH_UNRESPONSIVE_ENGINES_PREFIX)}")
                // 这两个已经是自解释的人话，只需要脱掉仓库层的机器码前缀，免得 UI 里出现双层前缀。
                message.startsWith(WEB_SEARCH_QUOTA_EXHAUSTED_PREFIX) ->
                    call.errorResult("web_search_failed:${message.removePrefix(WEB_SEARCH_QUOTA_EXHAUSTED_PREFIX)}")
                message.startsWith(WEB_SEARCH_BACKEND_REJECTED_PREFIX) ->
                    call.errorResult("web_search_failed:search backend rejected the query: ${message.removePrefix(WEB_SEARCH_BACKEND_REJECTED_PREFIX)}")
                else -> call.errorResult("web_search_failed:$message")
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
private const val WEB_SEARCH_QUOTA_EXHAUSTED_PREFIX = "web_search_quota_exhausted:"
private const val WEB_SEARCH_BACKEND_REJECTED_PREFIX = "web_search_backend_rejected:"
