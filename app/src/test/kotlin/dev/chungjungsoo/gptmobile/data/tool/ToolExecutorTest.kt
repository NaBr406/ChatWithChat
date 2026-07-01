package dev.chungjungsoo.gptmobile.data.tool

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchResult
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorTest {

    @Test
    fun `web search tool returns formatted search results`() = runBlocking {
        val searchRepository = FakeWebSearchRepository(
            listOf(
                result("One", "https://one.example", "first"),
                result("Duplicate", "https://one.example", "duplicate"),
                result("Two", "https://two.example", "second")
            )
        )
        val executor = builtInExecutor(searchRepository)

        val toolResult = executor.execute(
            ToolCall(
                id = "call_1",
                name = "web_search",
                arguments = """{"query":"latest Android SDK"}"""
            ),
            ToolLoopConfig(maxSearchResults = 2, maxToolResultChars = 1_000)
        )

        assertFalse(toolResult.isError)
        assertEquals("web_search", toolResult.name)
        assertEquals("2", toolResult.metadata["result_count"])
        assertTrue(toolResult.content.contains("Web search results for: latest Android SDK"))
        assertTrue(toolResult.content.contains("1. One"))
        assertTrue(toolResult.content.contains("2. Two"))
        assertFalse(toolResult.content.contains("Duplicate"))
        assertEquals("latest Android SDK", searchRepository.queries.single())
        assertEquals(2, searchRepository.limits.single())
    }

    @Test
    fun `fetch url tool returns title and page excerpt`() = runBlocking {
        withServer(
            body = """
            <html>
              <head>
                <title>Example Page</title>
                <meta property="og:site_name" content="Example Site">
              </head>
              <body><main><p>Readable text for the page.</p></main></body>
            </html>
            """.trimIndent()
        ) { baseUrl ->
            val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

            val toolResult = executor.execute(
                ToolCall(
                    id = "call_2",
                    name = "fetch_url",
                    arguments = """{"url":"$baseUrl/post"}"""
                ),
                ToolLoopConfig(allowPrivateNetworkFetch = true)
            )

            assertFalse(toolResult.isError)
            assertEquals("fetch_url", toolResult.name)
            assertEquals("$baseUrl/post", toolResult.metadata["url"])
            assertEquals("Example Page", toolResult.metadata["title"])
            assertTrue(toolResult.content.contains("Title: Example Page"))
            assertTrue(toolResult.content.contains("Site: Example Site"))
            assertTrue(toolResult.content.contains("Readable text for the page."))
        }
    }

    @Test
    fun `unknown tool returns error result`() = runBlocking {
        val executor = ToolExecutor(
            ToolRegistry(
                definitions = emptyList(),
                handlers = emptyMap()
            )
        )

        val toolResult = executor.execute(
            ToolCall(
                id = "call_3",
                name = "missing_tool",
                arguments = "{}"
            )
        )

        assertTrue(toolResult.isError)
        assertEquals("missing_tool", toolResult.name)
        assertTrue(toolResult.content.contains("unknown_tool:missing_tool"))
    }

    @Test
    fun `tool timeout returns error result`() = runBlocking {
        val executor = ToolExecutor(
            ToolRegistry(
                definitions = listOf(
                    ToolDefinition(
                        name = "slow_tool",
                        description = "Sleeps longer than the timeout.",
                        parameters = ToolDefinition.Parameters()
                    )
                ),
                handlers = mapOf(
                    "slow_tool" to ToolHandler { call, _ ->
                        delay(1_000)
                        ToolResult(call.id, call.name, "done")
                    }
                )
            )
        )

        val toolResult = executor.execute(
            ToolCall(
                id = "call_4",
                name = "slow_tool",
                arguments = "{}"
            ),
            ToolLoopConfig(toolTimeoutSeconds = 0)
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("tool_timeout:slow_tool"))
    }

    @Test
    fun `invalid fetch url returns error result`() = runBlocking {
        val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

        val toolResult = executor.execute(
            ToolCall(
                id = "call_5",
                name = "fetch_url",
                arguments = """{"url":"file:///etc/passwd"}"""
            )
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("invalid_url_scheme"))
    }

    @Test
    fun `private fetch url is rejected by default`() = runBlocking {
        val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

        val toolResult = executor.execute(
            ToolCall(
                id = "call_6",
                name = "fetch_url",
                arguments = """{"url":"http://127.0.0.1/private"}"""
            )
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("private_url_rejected"))
    }

    @Test
    fun `blocked fetch url domain is rejected`() = runBlocking {
        val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

        val toolResult = executor.execute(
            ToolCall(
                id = "call_7",
                name = "fetch_url",
                arguments = """{"url":"https://news.example.com/article"}"""
            ),
            ToolLoopConfig(fetchUrlBlockedDomains = setOf("example.com"))
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("blocked_domain:example.com"))
    }

    @Test
    fun `web search backend not configured returns clear tool error`() = runBlocking {
        val executor = builtInExecutor(
            FakeWebSearchRepository(
                results = emptyList(),
                failure = IllegalStateException("web_search_backend_not_configured")
            )
        )

        val toolResult = executor.execute(
            ToolCall(
                id = "call_8",
                name = "web_search",
                arguments = """{"query":"news"}"""
            )
        )

        assertTrue(toolResult.isError)
        assertEquals("web_search_backend_not_configured", toolResult.content)
    }

    @Test
    fun `web search unavailable engines return readable tool error`() = runBlocking {
        val executor = builtInExecutor(
            FakeWebSearchRepository(
                results = emptyList(),
                failure = IllegalStateException("web_search_no_results_unresponsive_engines:mojeek: access denied")
            )
        )

        val toolResult = executor.execute(
            ToolCall(
                id = "call_9",
                name = "web_search",
                arguments = """{"query":"news"}"""
            )
        )

        assertTrue(toolResult.isError)
        assertEquals("web_search_failed:search backend unavailable: mojeek: access denied", toolResult.content)
    }

    private fun builtInExecutor(searchRepository: WebSearchRepository): ToolExecutor = ToolExecutor(
        BuiltInTools(
            webSearchRepository = searchRepository,
            webPageExtractor = WebPageExtractor(NetworkClient(CIO))
        ).registry()
    )

    private fun result(
        title: String,
        url: String,
        snippet: String
    ) = WebSearchResult(
        title = title,
        url = url,
        snippet = snippet,
        source = "test"
    )

    private class FakeWebSearchRepository(
        private val results: List<WebSearchResult>,
        private val failure: Throwable? = null
    ) : WebSearchRepository {
        val queries = mutableListOf<String>()
        val limits = mutableListOf<Int>()

        override suspend fun search(
            query: String,
            limit: Int
        ): Result<List<WebSearchResult>> {
            queries += query
            limits += limit
            failure?.let { return Result.failure(it) }
            return Result.success(results)
        }
    }

    private suspend fun withServer(
        body: String,
        block: suspend (baseUrl: String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.respond(body)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(body: String) {
        responseHeaders.add("Content-Type", "text/html")
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
