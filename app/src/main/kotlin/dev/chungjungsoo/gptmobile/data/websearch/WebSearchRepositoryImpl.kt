package dev.chungjungsoo.gptmobile.data.websearch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
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
