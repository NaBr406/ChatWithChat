package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.websearch.FetchedWebPage
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchResult
import java.net.InetAddress
import java.net.URI
import java.util.Locale
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
        val validation = url.validateFetchUrl(config).getOrElse { throwable ->
            return call.errorResult(throwable.message ?: "invalid_url")
        }

        val page = webPageExtractor.fetchAndExtract(validation.toString()).getOrElse { throwable ->
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

    private fun String.validateFetchUrl(config: ToolLoopConfig): Result<URI> = runCatching {
        val uri = URI(trim())
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            throw IllegalArgumentException("invalid_url_scheme")
        }

        val host = uri.host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase(Locale.US)
            .orEmpty()
        if (host.isBlank()) {
            throw IllegalArgumentException("invalid_url_host")
        }
        host.blockedDomain(config.fetchUrlBlockedDomains)?.let { blockedDomain ->
            throw IllegalArgumentException("blocked_domain:$blockedDomain")
        }
        if (!config.allowPrivateNetworkFetch && host.isPrivateOrLocalHost()) {
            throw IllegalArgumentException("private_url_rejected")
        }

        uri
    }

    private fun String.blockedDomain(blockedDomains: Set<String>): String? {
        val host = this
        return blockedDomains
            .map { it.trim().trimStart('.').trimEnd('.').lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .firstOrNull { blockedDomain ->
                host == blockedDomain || host.endsWith(".$blockedDomain")
            }
    }

    private fun String.isPrivateOrLocalHost(): Boolean {
        if (this == "localhost" || endsWith(".localhost")) return true

        val addresses = runCatching { InetAddress.getAllByName(this).toList() }
            .getOrElse { return true }
        return addresses.any { it.isPrivateOrLocalAddress() }
    }

    private fun InetAddress.isPrivateOrLocalAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return true
        }

        val bytes = address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val first = bytes[0]
            val second = bytes[1]
            return first == 0 ||
                first == 10 ||
                first == 127 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)
        }

        if (bytes.size == 16) {
            val first = bytes[0]
            val second = bytes[1]
            return first in 0xfc..0xfd ||
                (first == 0xfe && second in 0x80..0xbf)
        }

        return false
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
private const val WEB_SEARCH_UNRESPONSIVE_ENGINES_PREFIX = "web_search_no_results_unresponsive_engines:"
