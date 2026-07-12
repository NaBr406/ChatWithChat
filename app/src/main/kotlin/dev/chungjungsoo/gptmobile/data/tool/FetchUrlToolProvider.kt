package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.database.entity.safeHttpUrlOrNull
import dev.chungjungsoo.gptmobile.data.websearch.FetchedWebPage
import dev.chungjungsoo.gptmobile.data.websearch.WebFetchRequestPolicy
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor

class FetchUrlToolProvider(
    private val webPageExtractor: WebPageExtractor
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.FetchUrl

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        category = ToolCategory.Web,
        defaultEnabled = true,
        isSensitive = false,
        presentationKey = "fetch_url",
        iconKey = "language"
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic
    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 2,
        maxCallsPerChat = 4,
        maxCallsPerRequestErrorKey = "max_fetched_urls_per_request",
        maxCallsPerChatErrorKey = "max_fetched_urls_per_chat"
    )

    override fun progressLabel(call: ToolCall): String = call.stringArgument("url")
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: definition.name

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
        val url = call.stringArgument("url").getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}")
        }
        val page = webPageExtractor.fetchAndExtract(
            url = url,
            requestPolicy = WebFetchRequestPolicy(
                blockedDomains = config.fetchUrlBlockedDomains,
                allowPrivateNetwork = config.allowPrivateNetworkFetch
            )
        ).getOrElse { throwable ->
            return call.errorResult("fetch_url_failed:${throwable.message ?: throwable::class.simpleName.orEmpty()}")
        }

        return ToolResult(
            callId = call.id,
            name = call.name,
            content = formatFetchedPage(page, config.maxFetchedPageChars),
            metadata = buildMap {
                put("url", page.url)
                put("source_tool", call.name)
                page.title.takeIf { it.isNotBlank() }?.let { put("title", it) }
                page.siteName?.takeIf { it.isNotBlank() }?.let { put("site_name", it) }
                page.excerpt.takeIf { it.isNotBlank() }?.let { put("snippet", it.clip(MAX_SOURCE_SNIPPET_CHARS)) }
            },
            sources = page.url.safeHttpUrlOrNull()?.let { safeUrl ->
                listOf(
                    ToolSource.PublicUrl(
                        title = page.title.ifBlank { safeUrl },
                        url = safeUrl,
                        snippet = page.excerpt.clip(MAX_SOURCE_SNIPPET_CHARS)
                    )
                )
            }.orEmpty()
        )
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
