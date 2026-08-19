package cn.nabr.chatwithchat.data.websearch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.repository.SettingRepository
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
            WebSearchConfig(
                backend = settingRepository.fetchWebSearchBackend(),
                searxngBaseUrl = settingRepository.fetchWebSearchSearxngBaseUrl(),
                anySearchApiKey = settingRepository.fetchWebSearchAnySearchApiKey()
            )
        }
    )

    override suspend fun search(query: String, limit: Int): Result<List<WebSearchResult>> = withContext(Dispatchers.IO) {
        val activeConfig = configProvider?.invoke() ?: config
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@withContext Result.success(emptyList())

        val boundedLimit = limit.coerceAtMost(activeConfig.maxResults).coerceAtLeast(0)
        if (boundedLimit == 0) return@withContext Result.success(emptyList())

        runCatching {
            when (activeConfig.backend) {
                WebSearchBackend.Searxng -> searchWithSearxng(normalizedQuery, boundedLimit, activeConfig)
                WebSearchBackend.AnySearch -> searchWithAnySearch(normalizedQuery, boundedLimit, activeConfig)
            }
        }
    }

    private suspend fun searchWithSearxng(
        query: String,
        limit: Int,
        activeConfig: WebSearchConfig
    ): List<WebSearchResult> {
        val baseUrl = activeConfig.searxngBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) throw IllegalStateException(WEB_SEARCH_NOT_CONFIGURED)

        val responseBody = httpClient.prepareGet("$baseUrl/search") {
            parameter("q", query)
            parameter("format", "json")
            applyTimeout(activeConfig)
        }.execute { response -> response.readSuccessfulBody() }

        val searchResponse = NetworkClient.json.decodeFromString<SearxngSearchResponse>(responseBody)
        val results = searchResponse
            .results
            .mapNotNull { it.toWebSearchResult() }
            .distinctBy { it.url }
            .rankForQuery(query)
            .take(limit)

        if (results.isEmpty() && searchResponse.unresponsiveEngines.isNotEmpty()) {
            throw IllegalStateException("web_search_no_results_unresponsive_engines:${searchResponse.unresponsiveEngines.toReadableSummary()}")
        }

        return results
    }

    /**
     * AnySearch 没有自建这条路，只能打厂商托管的实例。
     *
     * API Key 允许留空：匿名调用也能通，只是速率上限按来源 IP 算，移动网络下多个用户可能共用出口 IP
     * 互相挤，因此留空只够轻用。配额打满时它回 402，单独翻成一条自解释的错误，免得模型只看到裸状态码。
     */
    private suspend fun searchWithAnySearch(
        query: String,
        limit: Int,
        activeConfig: WebSearchConfig
    ): List<WebSearchResult> {
        val baseUrl = activeConfig.anySearchBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) throw IllegalStateException(WEB_SEARCH_NOT_CONFIGURED)
        val apiKey = activeConfig.anySearchApiKey.trim()

        val responseBody = httpClient.preparePost("$baseUrl$ANYSEARCH_SEARCH_PATH") {
            if (apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            setBody(
                AnySearchSearchRequest(
                    query = query,
                    maxResults = limit.coerceIn(1, ANYSEARCH_MAX_RESULTS)
                )
            )
            applyTimeout(activeConfig)
        }.execute { response ->
            if (response.status.value == ANYSEARCH_QUOTA_EXHAUSTED_STATUS) {
                throw IllegalStateException("web_search_quota_exhausted:AnySearch quota exhausted, set an API key or retry later")
            }
            response.readSuccessfulBody()
        }

        // 业务码和 HTTP 状态码是两套：这边 200 配 code != 0 也是失败，不判就会当成零结果。
        val searchResponse = NetworkClient.json.decodeFromString<AnySearchSearchResponse>(responseBody)
        if (searchResponse.code != ANYSEARCH_SUCCESS_CODE) {
            val detail = searchResponse.message.orEmpty().take(MAX_ERROR_BODY_LENGTH)
            throw IllegalStateException("web_search_backend_rejected:code=${searchResponse.code} $detail".trim())
        }

        return searchResponse.data
            ?.results
            .orEmpty()
            .mapNotNull { it.toWebSearchResult() }
            .distinctBy { it.url }
            .rankForQuery(query)
            .take(limit)
    }
}

private const val MAX_ERROR_BODY_LENGTH = 500
private const val WEB_SEARCH_NOT_CONFIGURED = "web_search_backend_not_configured"

/** AnySearch 单次最多回 10 条，超了它自己会拒。 */
private const val ANYSEARCH_MAX_RESULTS = 10
private const val ANYSEARCH_SUCCESS_CODE = 0
private const val ANYSEARCH_QUOTA_EXHAUSTED_STATUS = 402
private const val ANYSEARCH_SEARCH_PATH = "/v1/search"

private fun HttpRequestBuilder.applyTimeout(activeConfig: WebSearchConfig) {
    timeout {
        requestTimeoutMillis = activeConfig.timeoutMillis
        connectTimeoutMillis = activeConfig.timeoutMillis
        socketTimeoutMillis = activeConfig.timeoutMillis
    }
}

private suspend fun HttpResponse.readSuccessfulBody(): String {
    if (!status.isSuccess()) {
        throw IllegalStateException("web_search_http_${status.value}:${body<String>().take(MAX_ERROR_BODY_LENGTH)}")
    }
    return body<String>()
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
    val backend: WebSearchBackend = WebSearchBackend.Searxng,
    val searxngBaseUrl: String = "",
    /** AnySearch 只有一个托管实例，不做成设置项；留成参数是为了测试能指到本地服务器。 */
    val anySearchBaseUrl: String = DEFAULT_ANYSEARCH_BASE_URL,
    val anySearchApiKey: String = "",
    val timeoutMillis: Long = 10_000L,
    val maxResults: Int = 5
)

internal const val DEFAULT_ANYSEARCH_BASE_URL = "https://api.anysearch.com"

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
