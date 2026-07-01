package dev.chungjungsoo.gptmobile.data.websearch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class WebPageExtractor(
    private val httpClient: HttpClient,
    private val config: WebPageExtractorConfig = WebPageExtractorConfig()
) {

    constructor(
        networkClient: NetworkClient,
        config: WebPageExtractorConfig = WebPageExtractorConfig()
    ) : this(networkClient(), config)

    suspend fun fetchAndExtract(url: String): Result<FetchedWebPage> = withContext(Dispatchers.IO) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("url_required"))
        }

        runCatching {
            val html = httpClient.prepareGet(normalizedUrl) {
                timeout {
                    requestTimeoutMillis = config.timeoutMillis
                    connectTimeoutMillis = config.timeoutMillis
                    socketTimeoutMillis = config.timeoutMillis
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("web_page_http_${response.status.value}:${response.body<String>().take(MAX_ERROR_BODY_LENGTH)}")
                }
                response.body<String>()
            }

            extract(normalizedUrl, html, config.maxTextChars)
        }
    }

    fun extract(url: String, html: String, maxTextChars: Int = config.maxTextChars): FetchedWebPage {
        val document = Jsoup.parse(html, url)
        document.select("script, style, noscript").remove()

        val title = document.title().trim()
        val siteName = document
            .selectFirst("meta[property=og:site_name]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val rawText = document.body().text()
        val text = rawText
            .normalizeWhitespace()
            .take(maxTextChars.coerceAtLeast(0))
        val excerpt = text.take(config.maxExcerptChars.coerceAtLeast(0).coerceAtMost(text.length))

        return FetchedWebPage(
            url = url,
            title = title,
            text = text,
            excerpt = excerpt,
            siteName = siteName
        )
    }

    private fun String.normalizeWhitespace(): String = replace(WHITESPACE_REGEX, " ").trim()

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val MAX_ERROR_BODY_LENGTH = 500
    }
}

data class WebPageExtractorConfig(
    val timeoutMillis: Long = 10_000L,
    val maxTextChars: Int = 20_000,
    val maxExcerptChars: Int = 1_000
)
