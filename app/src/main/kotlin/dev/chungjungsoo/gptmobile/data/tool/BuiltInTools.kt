package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository

class BuiltInTools(
    private val webSearchRepository: WebSearchRepository,
    private val webPageExtractor: WebPageExtractor
) {
    fun providers(): List<ToolProvider> = listOf(
        WebSearchToolProvider(webSearchRepository),
        FetchUrlToolProvider(webPageExtractor),
        CurrentDateTimeToolProvider()
    )

    fun registry(): ToolRegistry = ToolRegistry(providers())
}
