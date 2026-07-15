package cn.nabr.chatwithchat.data.websearch

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import cn.nabr.chatwithchat.data.network.NetworkClient
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchRepositoryImplTest {

    @Test
    fun `search parses searxng json into web search results`() = runBlocking {
        withServer(
            statusCode = 200,
            body = """
            {
              "results": [
                {
                  "title": "Android target SDK",
                  "url": "https://developer.android.com/example",
                  "content": "Target SDK details",
                  "engine": "google",
                  "publishedDate": "2026-01-01"
                },
                {
                  "title": "Duplicate",
                  "url": "https://developer.android.com/example",
                  "content": "Duplicate result",
                  "engine": "bing"
                },
                {
                  "title": "Kotlin",
                  "url": "https://kotlinlang.org",
                  "content": "Kotlin details",
                  "engine": "duckduckgo"
                }
              ]
            }
            """.trimIndent()
        ) { baseUrl, _ ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl, maxResults = 5)
            )

            val results = repository.search("android kotlin", limit = 10).getOrThrow()

            assertEquals(2, results.size)
            val androidResult = results.first { it.url == "https://developer.android.com/example" }
            assertEquals(
                WebSearchResult(
                    title = "Android target SDK",
                    url = "https://developer.android.com/example",
                    snippet = "Target SDK details",
                    source = "google",
                    publishedAt = "2026-01-01"
                ),
                androidResult
            )
            assertTrue(results.any { it.url == "https://kotlinlang.org" })
        }
    }

    @Test
    fun `search filters search engine redirects and ranks matching local results`() = runBlocking {
        withServer(
            statusCode = 200,
            body = """
            {
              "results": [
                {
                  "title": "How to get help in Windows - Microsoft Support",
                  "url": "https://www.bing.com/ck/a?u=a1aHR0cHM6Ly9zdXBwb3J0Lm1pY3Jvc29mdC5jb20vZW4tdXMvd2luZG93cw&ntb=1",
                  "content": "Search for help in Windows settings and apps.",
                  "engine": "bing web html"
                },
                {
                  "title": "荆门天气预报_2026年07月02日荆门市天气 - 东方天气",
                  "url": "https://tianqi.eastday.com/tianqi/jingmen/20260702.html",
                  "content": "今天是2026年07月02日。荆门天气关键词是阴。",
                  "engine": "presearch"
                },
                {
                  "title": "荆门-天气预报 - 中央气象台",
                  "url": "http://www.nmc.cn/publish/forecast/AHB/jingmen.html",
                  "content": "湖北荆门天气预报 中央气象台",
                  "engine": "presearch"
                }
              ]
            }
            """.trimIndent()
        ) { baseUrl, _ ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl, maxResults = 5)
            )

            val results = repository.search("荆门市 天气预报 2026-07-02 湖北", limit = 5).getOrThrow()

            assertEquals(2, results.size)
            assertTrue(results.none { it.url.contains("bing.com") })
            assertEquals("http://www.nmc.cn/publish/forecast/AHB/jingmen.html", results[0].url)
            assertEquals("https://tianqi.eastday.com/tianqi/jingmen/20260702.html", results[1].url)
        }
    }

    @Test
    fun `search sends searxng json query parameters`() = runBlocking {
        withServer(
            statusCode = 200,
            body = """{"results": []}"""
        ) { baseUrl, requests ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl, maxResults = 5)
            )

            repository.search("android sdk", limit = 10).getOrThrow()

            assertEquals("/search?q=android+sdk&format=json", requests.single())
        }
    }

    @Test
    fun `blank search returns empty list without calling backend`() = runBlocking {
        withServer(
            statusCode = 200,
            body = """{"results": []}"""
        ) { baseUrl, requests ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl)
            )

            val results = repository.search("   ", limit = 5).getOrThrow()

            assertTrue(results.isEmpty())
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `zero limit returns empty list without calling backend`() = runBlocking {
        withServer(
            statusCode = 200,
            body = """{"results": []}"""
        ) { baseUrl, requests ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl)
            )

            val results = repository.search("android", limit = 0).getOrThrow()

            assertTrue(results.isEmpty())
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `blank backend returns configured error`() = runBlocking {
        val repository = WebSearchRepositoryImpl(
            httpClient = NetworkClient(CIO)(),
            config = WebSearchConfig(searxngBaseUrl = "")
        )

        val result = repository.search("android", limit = 5)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_search_backend_not_configured"))
    }

    @Test
    fun `http failure returns result failure`() = runBlocking {
        withServer(
            statusCode = 500,
            body = "unavailable"
        ) { baseUrl, _ ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl)
            )

            val result = repository.search("android", limit = 5)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_search_http_500"))
        }
    }

    @Test
    fun `empty results with unresponsive engines returns failure`() = runBlocking {
        withServer(
            statusCode = 200,
            body =
                """
                {
                  "results": [],
                  "unresponsive_engines": [
                    ["mojeek", "Suspended: access denied"]
                  ]
                }
                """.trimIndent()
        ) { baseUrl, _ ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl)
            )

            val result = repository.search("news", limit = 5)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_search_no_results_unresponsive_engines"))
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("mojeek"))
        }
    }

    @Test
    fun `results are kept when some engines are unresponsive`() = runBlocking {
        withServer(
            statusCode = 200,
            body =
                """
                {
                  "results": [
                    {"title": "One", "url": "https://one.example", "content": "one"}
                  ],
                  "unresponsive_engines": [
                    ["mojeek", "Suspended: access denied"]
                  ]
                }
                """.trimIndent()
        ) { baseUrl, _ ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl)
            )

            val results = repository.search("news", limit = 5).getOrThrow()

            assertEquals(1, results.size)
            assertEquals("https://one.example", results.single().url)
        }
    }

    @Test
    fun `result count is limited`() = runBlocking {
        withServer(
            statusCode = 200,
            body =
                """
                {
                  "results": [
                    {"title": "One", "url": "https://one.example", "content": "one"},
                    {"title": "Two", "url": "https://two.example", "content": "two"},
                    {"title": "Three", "url": "https://three.example", "content": "three"}
                  ]
                }
                """.trimIndent()
        ) { baseUrl, _ ->
            val repository = WebSearchRepositoryImpl(
                httpClient = NetworkClient(CIO)(),
                config = WebSearchConfig(searxngBaseUrl = baseUrl, maxResults = 2)
            )

            val results = repository.search("android", limit = 10).getOrThrow()

            assertEquals(2, results.size)
        }
    }

    private suspend fun withServer(
        statusCode: Int,
        body: String,
        block: suspend (baseUrl: String, requests: List<String>) -> Unit
    ) {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            exchange.respond(statusCode, body)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", requests)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(statusCode: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(statusCode, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
