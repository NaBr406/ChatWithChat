package dev.chungjungsoo.gptmobile.data.websearch

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageExtractorTest {

    @Test
    fun `html extraction returns title and readable text`() = runBlocking {
        withServer(
            statusCode = 200,
            body = """
            <html>
              <head>
                <title>Example Page</title>
                <meta property="og:site_name" content="Example Site">
                <style>.hidden { display: none; }</style>
                <script>console.log("secret")</script>
              </head>
              <body>
                <main>
                  <h1>Hello World</h1>
                  <p>This is readable text.</p>
                </main>
              </body>
            </html>
            """.trimIndent()
        ) { baseUrl ->
            val extractor = WebPageExtractor(
                httpClient = NetworkClient(CIO)()
            )

            val page = extractor.fetchAndExtract("$baseUrl/post").getOrThrow()

            assertEquals("$baseUrl/post", page.url)
            assertEquals("Example Page", page.title)
            assertEquals("Example Site", page.siteName)
            assertTrue(page.text.contains("Hello World"))
            assertTrue(page.text.contains("This is readable text."))
            assertFalse(page.text.contains("console.log"))
        }
    }

    @Test
    fun `oversized page text is clipped`() {
        val extractor = WebPageExtractor(
            httpClient = NetworkClient(CIO)(),
            config = WebPageExtractorConfig(maxTextChars = 12, maxExcerptChars = 6)
        )

        val page = extractor.extract(
            url = "https://example.com",
            html = "<html><head><title>Long</title></head><body>Alpha beta gamma delta</body></html>",
            maxTextChars = 12
        )

        assertEquals("Alpha beta g", page.text)
        assertEquals("Alpha ", page.excerpt)
    }

    @Test
    fun `http failure returns result failure`() = runBlocking {
        withServer(
            statusCode = 404,
            body = "gone"
        ) { baseUrl ->
            val extractor = WebPageExtractor(
                httpClient = NetworkClient(CIO)()
            )

            val result = extractor.fetchAndExtract("$baseUrl/missing")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_page_http_404"))
        }
    }

    @Test
    fun `blank url returns result failure`() = runBlocking {
        val extractor = WebPageExtractor(httpClient = NetworkClient(CIO)())

        val result = extractor.fetchAndExtract("   ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    private suspend fun withServer(
        statusCode: Int,
        body: String,
        block: suspend (baseUrl: String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.respond(statusCode, body)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(statusCode: Int, body: String) {
        responseHeaders.add("Content-Type", "text/html")
        val bytes = body.toByteArray()
        sendResponseHeaders(statusCode, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
