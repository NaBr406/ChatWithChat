package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.websearch.FetchedWebPage
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchResult
import java.net.URI
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class BuiltInTools(
    private val webSearchRepository: WebSearchRepository,
    private val webPageExtractor: WebPageExtractor
) {
    fun registry(): ToolRegistry = ToolRegistry(
        definitions = ToolDefinition.BuiltIns,
        handlers = mapOf(
            ToolDefinition.WebSearch.name to ToolHandler(::executeWebSearch),
            ToolDefinition.FetchUrl.name to ToolHandler(::executeFetchUrl)
        )
    )

    private suspend fun executeWebSearch(
        call: ToolCall,
        config: ToolLoopConfig
    ): ToolResult {
        val query = call.stringArgument("query").getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}")
        }
        val resultLimit = config.maxSearchResults.coerceAtLeast(0)
        val results = webSearchRepository.search(query, resultLimit).getOrElse { throwable ->
            return call.errorResult("web_search_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}")
        }
        val boundedResults = results
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .take(resultLimit)

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = formatSearchResults(query, boundedResults).clip(config.maxToolResultChars),
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

    private suspend fun executeFetchUrl(
        call: ToolCall,
        config: ToolLoopConfig
    ): ToolResult {
        val url = call.stringArgument("url").getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}")
        }
        if (!url.isPublicHttpUrl()) {
            return call.errorResult("invalid_url_scheme")
        }

        val page = webPageExtractor.fetchAndExtract(url).getOrElse { throwable ->
            return call.errorResult("fetch_url_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}")
        }

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = formatFetchedPage(page, config.maxFetchedPageChars).clip(config.maxToolResultChars),
            metadata = buildMap {
                put("url", page.url)
                put("source_tool", call.name)
                page.title.takeIf { it.isNotBlank() }?.let { put("title", it) }
                page.siteName?.takeIf { it.isNotBlank() }?.let { put("site_name", it) }
                page.excerpt.takeIf { it.isNotBlank() }?.let { put("snippet", it.clip(MAX_SOURCE_SNIPPET_CHARS)) }
            }
        )
    }

    private fun ToolCall.stringArgument(argumentName: String): Result<String> = runCatching {
        val arguments = argumentsObject().getOrThrow()
        val value = arguments[argumentName]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        if (value.isBlank()) {
            throw IllegalArgumentException("${argumentName}_required")
        }
        value
    }

    private fun String.isPublicHttpUrl(): Boolean = runCatching {
        val uri = URI(trim())
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

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

    private fun formatFetchedPage(
        page: FetchedWebPage,
        maxFetchedPageChars: Int
    ): String = buildString {
        appendLine("Fetched web page:")
        appendLine("Title: ${page.title.ifBlank { "(untitled)" }}")
        appendLine("URL: ${page.url}")
        page.siteName?.takeIf { it.isNotBlank() }?.let { siteName ->
            appendLine("Site: $siteName")
        }
        appendLine("Excerpt:")
        appendLine(page.text.ifBlank { page.excerpt }.clip(maxFetchedPageChars))
    }.trim()

    private fun String.clip(maxChars: Int): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (length <= boundedMax) return this
        return take(boundedMax).trimEnd()
    }
}

private const val MAX_SOURCE_SNIPPET_CHARS = 240
