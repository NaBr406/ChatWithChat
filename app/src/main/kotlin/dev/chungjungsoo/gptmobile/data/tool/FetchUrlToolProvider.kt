package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata
import dev.chungjungsoo.gptmobile.data.websearch.FetchedWebPage
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import java.net.InetAddress
import java.net.URI
import java.util.Locale

class FetchUrlToolProvider(
    private val webPageExtractor: WebPageExtractor
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.FetchUrl

    override fun progressLabel(call: ToolCall): String = call.stringArgument("url")
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: definition.name

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
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

    override fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> {
        val url = result.metadata["url"]?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(
            MessageSourceMetadata(
                title = result.metadata["title"]?.trim()?.takeIf { it.isNotBlank() } ?: url,
                url = url,
                snippet = result.metadata["snippet"].orEmpty().clip(MAX_SOURCE_SNIPPET_CHARS),
                sourceToolName = result.metadata["source_tool"]?.trim()?.takeIf { it.isNotBlank() } ?: result.name
            )
        )
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
}

private const val MAX_SOURCE_SNIPPET_CHARS = 240
