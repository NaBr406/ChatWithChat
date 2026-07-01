package dev.chungjungsoo.gptmobile.data.websearch

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
    val publishedAt: String? = null
)

data class FetchedWebPage(
    val url: String,
    val title: String,
    val text: String,
    val excerpt: String,
    val siteName: String? = null
)

