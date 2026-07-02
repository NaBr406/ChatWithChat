package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository

class BuiltInTools(
    private val webSearchRepository: WebSearchRepository,
    private val webPageExtractor: WebPageExtractor,
    private val deviceLocationReader: DeviceLocationReader = UnavailableDeviceLocationReader
) {
    fun providers(): List<ToolProvider> = listOf(
        WebSearchToolProvider(webSearchRepository),
        FetchUrlToolProvider(webPageExtractor),
        CurrentDateTimeToolProvider(),
        DeviceLocationToolProvider(deviceLocationReader)
    )

    fun registry(): ToolRegistry = ToolRegistry(providers())
}
