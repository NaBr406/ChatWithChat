package dev.chungjungsoo.gptmobile.data.websearch

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
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
                <nav>Navigation boilerplate</nav>
                <main>
                  <h1>Hello World</h1>
                  <p>This is readable text.</p>
                </main>
                <footer>Footer boilerplate</footer>
              </body>
            </html>
            """.trimIndent()
        ) { baseUrl ->
            val extractor = WebPageExtractor()

            val page = extractor.fetchAndExtract(
                "$baseUrl/post",
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            ).getOrThrow()

            assertEquals("$baseUrl/post", page.url)
            assertEquals("Example Page", page.title)
            assertEquals("Example Site", page.siteName)
            assertTrue(page.text.contains("Hello World"))
            assertTrue(page.text.contains("This is readable text."))
            assertFalse(page.text.contains("console.log"))
            assertFalse(page.text.contains("Navigation boilerplate"))
            assertFalse(page.text.contains("Footer boilerplate"))
        }
    }

    @Test
    fun `oversized page text is clipped`() {
        val extractor = WebPageExtractor(
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
            val extractor = WebPageExtractor()

            val result = extractor.fetchAndExtract(
                "$baseUrl/missing",
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_page_http_404"))
        }
    }

    @Test
    fun `blank url returns result failure`() = runBlocking {
        val extractor = WebPageExtractor()

        val result = extractor.fetchAndExtract("   ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `redirects are followed manually`() = runBlocking {
        val requestCount = AtomicInteger()
        withServer(
            handler = { exchange ->
                requestCount.incrementAndGet()
                when (exchange.requestURI.path) {
                    "/start" -> exchange.respond(302, "", mapOf("Location" to "/final"))
                    else -> exchange.respond(200, "<html><body>redirected</body></html>")
                }
            }
        ) { baseUrl ->
            val extractor = WebPageExtractor()

            val page = extractor.fetchAndExtract(
                "$baseUrl/start",
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            ).getOrThrow()

            assertEquals("$baseUrl/final", page.url)
            assertEquals("redirected", page.text)
            assertEquals(2, requestCount.get())
        }
    }

    @Test
    fun `redirect target is revalidated before target request`() = runBlocking {
        val privateTargetRequests = AtomicInteger()
        withServer(
            handler = { exchange ->
                when (exchange.requestURI.path) {
                    "/start" -> exchange.respond(
                        302,
                        "",
                        mapOf("Location" to "http://127.0.0.1:${exchange.localAddress.port}/private")
                    )
                    else -> {
                        privateTargetRequests.incrementAndGet()
                        exchange.respond(200, "<html><body>private</body></html>")
                    }
                }
            }
        ) { baseUrl ->
            val extractor = WebPageExtractor()
            val initialUrl = baseUrl.replace("127.0.0.1", "localhost")

            val result = extractor.fetchAndExtract(
                "$initialUrl/start",
                WebFetchRequestPolicy(
                    blockedDomains = setOf("127.0.0.1"),
                    allowPrivateNetwork = true
                )
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("blocked_domain:127.0.0.1"))
            assertEquals(0, privateTargetRequests.get())
        }
    }

    @Test
    fun `host with any private DNS result is rejected`() = runBlocking {
        val extractor = WebPageExtractor(
            hostResolver = WebFetchHostResolver {
                listOf(
                    InetAddress.getByName("93.184.216.34"),
                    InetAddress.getByName("127.0.0.1")
                )
            }
        )

        val result = extractor.fetchAndExtract("https://public.example/page")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("private_url_rejected"))
    }

    @Test
    fun `non globally routable special addresses are rejected`() {
        val validator = WebFetchUrlValidator()
        val addresses = listOf(
            "192.88.99.1",
            "100::1",
            "100:0:0:1::1",
            "2001:2::1",
            "2001:10::1",
            "2001:20::1",
            "2001:30::1",
            "2001:100::1",
            "2001:db8::1",
            "2002::1",
            "3fff::1",
            "5f00::1"
        )

        addresses.forEach { address ->
            val result = runCatching {
                validator.validateResolvedAddresses(
                    listOf(InetAddress.getByName(address)),
                    WebFetchRequestPolicy()
                )
            }

            assertTrue("Expected $address to be rejected", result.isFailure)
            assertEquals("private_url_rejected", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `globally routable address boundaries are allowed`() {
        val validator = WebFetchUrlValidator()
        val addresses = listOf(
            "8.8.8.8",
            "192.31.196.1",
            "192.52.193.1",
            "192.175.48.1",
            "2001:1::1",
            "2001:1::2",
            "2001:1::3",
            "2001:3::1",
            "2001:4:112::1",
            "2001:200::1",
            "2606:4700:4700::1111",
            "3fff:1000::1"
        )

        addresses.forEach { address ->
            val result = runCatching {
                validator.validateResolvedAddresses(
                    listOf(InetAddress.getByName(address)),
                    WebFetchRequestPolicy()
                )
            }

            assertTrue("Expected $address to be allowed", result.isSuccess)
        }
    }

    @Test
    fun `validated DNS result is the address used for the connection`() = runBlocking {
        val resolutionCount = AtomicInteger()
        withServer(statusCode = 200, body = "<html><body>pinned</body></html>") { baseUrl ->
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(),
                hostResolver = WebFetchHostResolver {
                    if (resolutionCount.incrementAndGet() == 1) {
                        listOf(InetAddress.getByName("127.0.0.1"))
                    } else {
                        listOf(InetAddress.getByName("93.184.216.34"))
                    }
                }
            )
            val pinnedUrl = baseUrl.replace("127.0.0.1", "rebind.example")

            val page = extractor.fetchAndExtract(
                pinnedUrl,
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            ).getOrThrow()

            assertEquals("pinned", page.text)
            assertEquals(1, resolutionCount.get())
        }
    }

    @Test
    fun `redirect count is bounded`() = runBlocking {
        val requestCount = AtomicInteger()
        withServer(
            handler = { exchange ->
                requestCount.incrementAndGet()
                exchange.respond(302, "", mapOf("Location" to "/loop"))
            }
        ) { baseUrl ->
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(maxRedirects = 1)
            )

            val result = extractor.fetchAndExtract(
                "$baseUrl/loop",
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_page_too_many_redirects"))
            assertEquals(2, requestCount.get())
        }
    }

    @Test
    fun `redirect chain shares one deadline`() = runBlocking {
        val requestCount = AtomicInteger()
        withServer(
            handler = { exchange ->
                requestCount.incrementAndGet()
                Thread.sleep(200)
                if (exchange.requestURI.path == "/start") {
                    exchange.respond(302, "", mapOf("Location" to "/final"))
                } else {
                    exchange.respond(200, "<html><body>too late</body></html>")
                }
            }
        ) { baseUrl ->
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(timeoutMillis = 300, maxRedirects = 1)
            )
            val startedAt = System.nanoTime()

            val result = extractor.fetchAndExtract(
                "$baseUrl/start",
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(result.isFailure)
            assertEquals(2, requestCount.get())
            assertTrue("Expected shared deadline, elapsed=${elapsedMillis}ms", elapsedMillis < 800)
        }
    }

    @Test
    fun `cancelling fetch cancels active okhttp call`() = runBlocking {
        val releaseBody = CountDownLatch(1)
        val requestStarted = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        withServer(
            handler = { exchange ->
                requestStarted.countDown()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                exchange.sendResponseHeaders(200, 0)
                try {
                    releaseBody.await(2, TimeUnit.SECONDS)
                    exchange.responseBody.use { body -> body.write("<html><body>late</body></html>".toByteArray()) }
                } catch (_: IOException) {
                    exchange.close()
                }
            }
        ) { baseUrl ->
            val httpClient = OkHttpClient.Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            callCancelled.countDown()
                        }
                    }
                )
                .build()
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(timeoutMillis = 5_000),
                httpClient = httpClient
            )
            val fetch = async(Dispatchers.Default) {
                extractor.fetchAndExtract(
                    baseUrl,
                    WebFetchRequestPolicy(allowPrivateNetwork = true)
                )
            }

            try {
                assertTrue("Request did not reach the test server", requestStarted.await(1, TimeUnit.SECONDS))
                fetch.cancel()
                withTimeout(1_000) { fetch.join() }

                assertTrue("Fetch coroutine was not cancelled", fetch.isCancelled)
                assertTrue("OkHttp call did not emit canceled", callCancelled.await(1, TimeUnit.SECONDS))
            } finally {
                releaseBody.countDown()
                httpClient.connectionPool.evictAll()
                httpClient.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `response exceeding decompressed byte limit fails`() = runBlocking {
        withServer(statusCode = 200, body = "A".repeat(4_096)) { baseUrl ->
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(maxResponseBytes = 128)
            )

            val result = extractor.fetchAndExtract(
                baseUrl,
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_page_response_too_large"))
        }
    }

    @Test
    fun `gzip response exceeding decompressed byte limit fails`() = runBlocking {
        val compressedBody = gzip("B".repeat(32_768).toByteArray())
        val acceptEncoding = AtomicReference<String>()
        withServer(
            handler = { exchange ->
                acceptEncoding.set(exchange.requestHeaders.getFirst("Accept-Encoding"))
                exchange.respond(
                    statusCode = 200,
                    body = compressedBody,
                    headers = mapOf("Content-Encoding" to "gzip")
                )
            }
        ) { baseUrl ->
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(maxResponseBytes = 256)
            )

            val result = extractor.fetchAndExtract(
                baseUrl,
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_page_response_too_large"))
            assertEquals("identity", acceptEncoding.get())
        }
    }

    @Test
    fun `unterminated gzip filename is stopped by encoded byte limit`() = runBlocking {
        val maliciousBody = gzipWithUnterminatedFileName(4_096)
        withServer(
            handler = { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, 0)
                try {
                    exchange.responseBody.use { body -> body.write(maliciousBody) }
                } catch (_: IOException) {
                    exchange.close()
                }
            }
        ) { baseUrl ->
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(
                    maxEncodedResponseBytes = 128,
                    maxResponseBytes = 512
                )
            )

            val result = extractor.fetchAndExtract(
                baseUrl,
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_page_response_too_large"))
        }
    }

    @Test
    fun `encoded error body is bounded before decoding`() = runBlocking {
        withServer(
            handler = { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=UTF-8")
                exchange.sendResponseHeaders(404, 0)
                try {
                    exchange.responseBody.use { body -> body.write("E".repeat(4_096).toByteArray()) }
                } catch (_: IOException) {
                    exchange.close()
                }
            }
        ) { baseUrl ->
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(maxEncodedResponseBytes = 128)
            )

            val result = extractor.fetchAndExtract(
                baseUrl,
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_page_response_too_large"))
        }
    }

    @Test
    fun `gzip error body is bounded after decoding`() = runBlocking {
        val compressedBody = gzip("F".repeat(4_096).toByteArray())
        withServer(
            handler = { exchange ->
                exchange.respond(
                    statusCode = 404,
                    body = compressedBody,
                    headers = mapOf("Content-Encoding" to "gzip")
                )
            }
        ) { baseUrl ->
            val extractor = WebPageExtractor()

            val result = extractor.fetchAndExtract(
                baseUrl,
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )
            val message = result.exceptionOrNull()?.message.orEmpty()

            assertTrue(result.isFailure)
            assertTrue(message.startsWith("web_page_http_404:"))
            assertEquals("web_page_http_404:".length + 500, message.length)
        }
    }

    @Test
    fun `response exceeding decoded character limit fails`() = runBlocking {
        withServer(statusCode = 200, body = "C".repeat(256)) { baseUrl ->
            val extractor = WebPageExtractor(
                config = WebPageExtractorConfig(maxResponseBytes = 512, maxResponseChars = 64)
            )

            val result = extractor.fetchAndExtract(
                baseUrl,
                WebFetchRequestPolicy(allowPrivateNetwork = true)
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("web_page_response_too_large"))
        }
    }

    private suspend fun withServer(
        statusCode: Int,
        body: String,
        block: suspend (baseUrl: String) -> Unit
    ) = withServer(
        handler = { exchange -> exchange.respond(statusCode, body) },
        block = block
    )

    private suspend fun withServer(
        handler: (HttpExchange) -> Unit,
        block: suspend (baseUrl: String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/", handler)
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(
        statusCode: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ) = respond(statusCode, body.toByteArray(), headers)

    private fun HttpExchange.respond(
        statusCode: Int,
        body: ByteArray,
        headers: Map<String, String> = emptyMap()
    ) {
        responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        sendResponseHeaders(statusCode, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip -> gzip.write(bytes) }
        output.toByteArray()
    }

    private fun gzipWithUnterminatedFileName(size: Int): ByteArray = ByteArray(size).apply {
        require(size > GZIP_HEADER_BYTES)
        this[0] = 0x1f
        this[1] = 0x8b.toByte()
        this[2] = 0x08
        this[3] = 0x08
        fill('A'.code.toByte(), fromIndex = GZIP_HEADER_BYTES)
    }

    private companion object {
        const val GZIP_HEADER_BYTES = 10
    }
}
