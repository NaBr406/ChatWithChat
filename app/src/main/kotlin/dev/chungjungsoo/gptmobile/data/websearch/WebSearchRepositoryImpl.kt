package dev.chungjungsoo.gptmobile.data.websearch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebSearchRepositoryImpl(
    private val httpClient: HttpClient,
    private val config: WebSearchConfig = WebSearchConfig()
) : WebSearchRepository {

    constructor(
        networkClient: NetworkClient,
        config: WebSearchConfig = WebSearchConfig()
    ) : this(networkClient(), config)

    override suspend fun search(query: String, limit: Int): Result<List<WebSearchResult>> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@withContext Result.success(emptyList())

        val boundedLimit = limit.coerceAtMost(config.maxResults).coerceAtLeast(0)
        if (boundedLimit == 0) return@withContext Result.success(emptyList())

        val baseUrl = config.searxngBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("web_search_backend_not_configured"))
        }

        runCatching {
            val responseBody = httpClient.prepareGet("$baseUrl/search") {
                parameter("q", normalizedQuery)
                parameter("format", "json")
                timeout {
                    requestTimeoutMillis = config.timeoutMillis
                    connectTimeoutMillis = config.timeoutMillis
                    socketTimeoutMillis = config.timeoutMillis
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("web_search_http_${response.status.value}:${response.body<String>().take(MAX_ERROR_BODY_LENGTH)}")
                }
                response.body<String>()
            }

            NetworkClient.json.decodeFromString<SearxngSearchResponse>(responseBody)
                .results
                .mapNotNull { it.toWebSearchResult() }
                .distinctBy { it.url }
                .take(boundedLimit)
        }
    }

    companion object {
        private const val MAX_ERROR_BODY_LENGTH = 500
    }
}

data class WebSearchConfig(
    val searxngBaseUrl: String = "",
    val timeoutMillis: Long = 10_000L,
    val maxResults: Int = 5
)
