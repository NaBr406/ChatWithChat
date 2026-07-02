package dev.chungjungsoo.gptmobile.data.websearch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebSearchRepositoryImpl(
    private val httpClient: HttpClient,
    private val config: WebSearchConfig = WebSearchConfig(),
    private val configProvider: (suspend () -> WebSearchConfig)? = null
) : WebSearchRepository {

    constructor(
        networkClient: NetworkClient,
        config: WebSearchConfig = WebSearchConfig()
    ) : this(networkClient(), config)

    constructor(
        networkClient: NetworkClient,
        settingRepository: SettingRepository
    ) : this(
        httpClient = networkClient(),
        configProvider = {
            WebSearchConfig(searxngBaseUrl = settingRepository.fetchWebSearchSearxngBaseUrl())
        }
    )

    override suspend fun search(query: String, limit: Int): Result<List<WebSearchResult>> = withContext(Dispatchers.IO) {
        val activeConfig = configProvider?.invoke() ?: config
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@withContext Result.success(emptyList())

        val boundedLimit = limit.coerceAtMost(activeConfig.maxResults).coerceAtLeast(0)
        if (boundedLimit == 0) return@withContext Result.success(emptyList())

        val baseUrl = activeConfig.searxngBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("web_search_backend_not_configured"))
        }

        runCatching {
            val responseBody = httpClient.prepareGet("$baseUrl/search") {
                parameter("q", normalizedQuery)
                parameter("format", "json")
                timeout {
                    requestTimeoutMillis = activeConfig.timeoutMillis
                    connectTimeoutMillis = activeConfig.timeoutMillis
                    socketTimeoutMillis = activeConfig.timeoutMillis
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("web_search_http_${response.status.value}:${response.body<String>().take(MAX_ERROR_BODY_LENGTH)}")
                }
                response.body<String>()
            }

            val searchResponse = NetworkClient.json.decodeFromString<SearxngSearchResponse>(responseBody)
            val results = searchResponse
                .results
                .mapNotNull { it.toWebSearchResult() }
                .distinctBy { it.url }
                .rankForQuery(normalizedQuery)
                .take(boundedLimit)

            if (results.isEmpty() && searchResponse.unresponsiveEngines.isNotEmpty()) {
                throw IllegalStateException("web_search_no_results_unresponsive_engines:${searchResponse.unresponsiveEngines.toReadableSummary()}")
            }

            results
        }
    }

    companion object {
        private const val MAX_ERROR_BODY_LENGTH = 500
    }
}

private const val MAX_UNRESPONSIVE_ENGINE_DETAILS = 3

private fun List<List<String>>.toReadableSummary(): String =
    take(MAX_UNRESPONSIVE_ENGINE_DETAILS)
        .joinToString(separator = "; ") { parts ->
            parts
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(separator = ": ")
        }
        .ifBlank { "unknown" }

data class WebSearchConfig(
    val searxngBaseUrl: String = "",
    val timeoutMillis: Long = 10_000L,
    val maxResults: Int = 5
)

private const val MIN_RELEVANCE_SCORE = 2

private data class RankedWebSearchResult(
    val result: WebSearchResult,
    val relevanceScore: Int,
    val authorityScore: Int,
    val index: Int
) {
    val totalScore: Int = relevanceScore + authorityScore
}

private data class SearchTerm(
    val variants: Set<String>
)

private fun List<WebSearchResult>.rankForQuery(query: String): List<WebSearchResult> {
    val terms = query.searchTerms()
    if (terms.isEmpty()) return this

    val ranked = mapIndexed { index, result ->
        RankedWebSearchResult(
            result = result,
            relevanceScore = result.relevanceScore(terms),
            authorityScore = result.authorityScore(),
            index = index
        )
    }
    val hasRelevantResults = ranked.any { it.relevanceScore >= MIN_RELEVANCE_SCORE }
    val filtered = if (hasRelevantResults) {
        ranked.filter { it.relevanceScore > 0 }
    } else {
        ranked
    }

    return filtered
        .sortedWith(
            compareByDescending<RankedWebSearchResult> { it.totalScore }
                .thenBy { it.index }
        )
        .map { it.result }
}

private fun String.searchTerms(): List<SearchTerm> =
    SEARCH_TERM_REGEX
        .findAll(lowercase(Locale.US))
        .map { it.value.trim() }
        .filter { it.isUsefulSearchTerm() }
        .map { term ->
            SearchTerm(
                variants = buildSet {
                    add(term)
                    term.withoutChineseLocationSuffix()?.let { add(it) }
                }
            )
        }
        .distinctBy { it.variants.first() }
        .toList()

private fun String.isUsefulSearchTerm(): Boolean {
    if (length < 2) return false
    if (all { it.isDigit() }) return length >= 4
    return true
}

private fun String.withoutChineseLocationSuffix(): String? {
    if (length <= 2 || !containsHan()) return null
    return if (last() in CHINESE_LOCATION_SUFFIXES) {
        dropLast(1).takeIf { it.length >= 2 }
    } else {
        null
    }
}

private fun WebSearchResult.relevanceScore(terms: List<SearchTerm>): Int {
    val normalizedTitle = title.lowercase(Locale.US)
    val normalizedSnippet = snippet.lowercase(Locale.US)
    val normalizedUrl = url.lowercase(Locale.US)

    return terms.sumOf { term ->
        val titleHit = term.variants.any { normalizedTitle.contains(it) }
        val snippetHit = term.variants.any { normalizedSnippet.contains(it) }
        val urlHit = term.variants.any { normalizedUrl.contains(it) }

        (if (titleHit) 4 else 0) +
            (if (snippetHit) 2 else 0) +
            (if (urlHit) 1 else 0)
    }
}

private fun WebSearchResult.authorityScore(): Int {
    val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US).trimEnd('.') }
        .getOrDefault("")
    if (host.isBlank()) return 0

    return when {
        host.endsWith(".gov") || host.endsWith(".gov.cn") -> 4
        host == "nmc.cn" || host.endsWith(".nmc.cn") -> 4
        host == "who.int" || host.endsWith(".who.int") -> 4
        host == "developer.android.com" -> 3
        host == "kotlinlang.org" || host.endsWith(".kotlinlang.org") -> 3
        else -> 0
    }
}

private fun String.containsHan(): Boolean =
    any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

private val SEARCH_TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
private val CHINESE_LOCATION_SUFFIXES = setOf('市', '省', '县', '区', '州')
