package dev.chungjungsoo.gptmobile.data.websearch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearxngSearchResponse(
    val results: List<SearxngSearchResult> = emptyList(),
    @SerialName("unresponsive_engines")
    val unresponsiveEngines: List<List<String>> = emptyList()
)

@Serializable
data class SearxngSearchResult(
    val title: String? = null,
    val url: String? = null,
    val content: String? = null,
    val engine: String? = null,
    @SerialName("publishedDate")
    val publishedDate: String? = null
) {
    fun toWebSearchResult(): WebSearchResult? {
        val resolvedUrl = url?.trim().orEmpty()
        if (resolvedUrl.isBlank()) return null

        return WebSearchResult(
            title = title?.trim().orEmpty(),
            url = resolvedUrl,
            snippet = content?.trim().orEmpty(),
            source = engine?.trim()?.takeIf { it.isNotBlank() } ?: "searxng",
            publishedAt = publishedDate?.trim()?.takeIf { it.isNotBlank() }
        )
    }
}
