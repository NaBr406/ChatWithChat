package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchResult

class WebSearchToolProvider(
    private val webSearchRepository: WebSearchRepository
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.WebSearch
    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 2,
        maxCallsPerChat = 4,
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
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .take(resultLimit)

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = formatSearchResults(query, boundedResults),
            metadata = buildMap {
                put("result_count", boundedResults.size.toString())
                boundedResults.forEachIndexed { index, result ->
                    put("source_${index}_title", result.title)
                    put("source_${index}_url", result.url)
                    put("source_${index}_snippet", result.snippet)
                    put("source_${index}_tool", call.name)
                }
            }
        )
    }

    override fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> {
        val sources = mutableListOf<MessageSourceMetadata>()
        val count = result.metadata["result_count"]?.toIntOrNull()
        var index = 0
        while (count == null || index < count.coerceAtLeast(0)) {
            val url = result.metadata["source_${index}_url"]?.trim()?.takeIf { it.isNotBlank() } ?: break
            sources += MessageSourceMetadata(
                title = result.metadata["source_${index}_title"]?.trim()?.takeIf { it.isNotBlank() } ?: url,
                url = url,
                snippet = result.metadata["source_${index}_snippet"].orEmpty().clip(MAX_SOURCE_SNIPPET_CHARS),
                sourceToolName = result.metadata["source_${index}_tool"]?.trim()?.takeIf { it.isNotBlank() } ?: result.name
            )
            index += 1
        }
        return sources
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
