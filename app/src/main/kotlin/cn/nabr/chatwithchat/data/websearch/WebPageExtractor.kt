package cn.nabr.chatwithchat.data.websearch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class WebPageExtractor(
    private val config: WebPageExtractorConfig = WebPageExtractorConfig(),
    private val hostResolver: WebFetchHostResolver = systemWebFetchHostResolver,
    httpClient: OkHttpClient = OkHttpClient()
) {
    private val baseHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .build()
    private val urlValidator = WebFetchUrlValidator()

    init {
        require(config.timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(config.timeoutMillis <= Int.MAX_VALUE.toLong()) { "timeoutMillis is too large" }
        require(config.maxEncodedResponseBytes > 0) { "maxEncodedResponseBytes must be positive" }
        require(config.maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(config.maxResponseChars > 0) { "maxResponseChars must be positive" }
        require(config.maxRedirects >= 0) { "maxRedirects must not be negative" }
    }

    suspend fun fetchAndExtract(
        url: String,
        requestPolicy: WebFetchRequestPolicy = WebFetchRequestPolicy()
    ): Result<FetchedWebPage> = withContext(Dispatchers.IO) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("url_required"))
        }

        try {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis)
            val (finalUrl, html) = fetchResponseBody(normalizedUrl, requestPolicy, deadlineNanos)
            currentCoroutineContext().ensureActive()
            ensureBeforeDeadline(deadlineNanos)
            val page = extract(finalUrl, html, config.maxTextChars)
            currentCoroutineContext().ensureActive()
            ensureBeforeDeadline(deadlineNanos)
            Result.success(page)
        } catch (throwable: Throwable) {
            currentCoroutineContext().ensureActive()
            Result.failure(throwable)
        }
    }

    private suspend fun fetchResponseBody(
        initialUrl: String,
        requestPolicy: WebFetchRequestPolicy,
        deadlineNanos: Long
    ): Pair<String, String> {
        var currentUrl = initialUrl
        var redirectCount = 0
        val requestClient = baseHttpClient.newBuilder()
            .dns(PinnedWebFetchDns(hostResolver, requestPolicy, urlValidator))
            .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

        while (true) {
            currentCoroutineContext().ensureActive()
            ensureBeforeDeadline(deadlineNanos)
            val currentUri = urlValidator.validate(currentUrl, requestPolicy)
            val request = Request.Builder()
                .url(currentUri.toASCIIString())
                .header(ACCEPT_ENCODING_HEADER, IDENTITY_ENCODING)
                .get()
                .build()
            val call = requestClient.newCall(request)
            call.timeout().deadlineNanoTime(deadlineNanos)
            val response = call.awaitResponse { httpResponse ->
                when {
                    httpResponse.code in REDIRECT_STATUS_CODES -> FetchResponse.Redirect(
                        httpResponse.header(LOCATION_HEADER)
                            ?: throw IllegalStateException("web_page_redirect_missing_location")
                    )
                    !httpResponse.isSuccessful -> {
                        val errorBody = httpResponse.readDecodedText(
                            maxEncodedBytes = minOf(config.maxEncodedResponseBytes, MAX_ERROR_BODY_ENCODED_BYTES),
                            maxDecodedBytes = MAX_ERROR_BODY_BYTES,
                            failOnDecodedLimit = false
                        )
                        throw IllegalStateException("web_page_http_${httpResponse.code}:$errorBody")
                    }
                    else -> FetchResponse.Body(
                        httpResponse.readDecodedText(
                            maxEncodedBytes = config.maxEncodedResponseBytes,
                            maxDecodedBytes = config.maxResponseBytes,
                            failOnDecodedLimit = true
                        )
                    )
                }
            }

            when (response) {
                is FetchResponse.Body -> {
                    if (response.content.length > config.maxResponseChars) {
                        throw IllegalStateException("web_page_response_too_large")
                    }
                    return currentUri.toASCIIString() to response.content
                }
                is FetchResponse.Redirect -> {
                    if (redirectCount >= config.maxRedirects) {
                        throw IllegalStateException("web_page_too_many_redirects")
                    }
                    currentUrl = resolveRedirect(currentUri, response.location).toASCIIString()
                    redirectCount += 1
                }
            }
        }
    }

    private fun resolveRedirect(currentUri: URI, location: String): URI = runCatching {
        currentUri.resolve(URI(location.trim()))
    }.getOrElse { throw IllegalArgumentException("web_page_redirect_invalid_location", it) }

    private suspend fun Call.awaitResponse(transform: (Response) -> FetchResponse): FetchResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            runCatching {
                enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            continuation.resumeSafely(Result.failure(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (!continuation.isActive) {
                                response.close()
                                return
                            }
                            continuation.resumeSafely(runCatching { response.use(transform) })
                        }
                    }
                )
            }.onFailure { throwable -> continuation.resumeSafely(Result.failure(throwable)) }
        }

    private fun Response.readDecodedText(
        maxEncodedBytes: Int,
        maxDecodedBytes: Int,
        failOnDecodedLimit: Boolean
    ): String {
        val encoding = contentEncoding()
        val declaredLength = body.contentLength()
        if (declaredLength > maxEncodedBytes) {
            throw IllegalStateException("web_page_response_too_large")
        }
        val encodedBytes = body.byteStream().use { stream ->
            stream.readBoundedBytes(maxEncodedBytes, failOnLimit = true)
        }
        val decodedStream = when (encoding) {
            WebContentEncoding.Identity -> ByteArrayInputStream(encodedBytes)
            WebContentEncoding.Gzip -> GZIPInputStream(ByteArrayInputStream(encodedBytes), STREAM_BUFFER_BYTES)
        }
        val decodedBytes = decodedStream.use { stream ->
            stream.readBoundedBytes(maxDecodedBytes, failOnDecodedLimit)
        }
        return decodedBytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }

    private fun Response.contentEncoding(): WebContentEncoding {
        val encodings = headers.values(CONTENT_ENCODING_HEADER)
            .flatMap { value -> value.split(',') }
            .map { value -> value.trim().lowercase(Locale.US) }
            .filter { value -> value.isNotBlank() && value != IDENTITY_ENCODING }
        return when (encodings) {
            emptyList<String>() -> WebContentEncoding.Identity
            listOf(GZIP_ENCODING) -> WebContentEncoding.Gzip
            else -> throw IllegalStateException("web_page_unsupported_content_encoding")
        }
    }

    private fun InputStream.readBoundedBytes(maxBytes: Int, failOnLimit: Boolean): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, STREAM_BUFFER_BYTES))
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var totalBytes = 0
        while (true) {
            if (!failOnLimit && totalBytes == maxBytes) break
            val remainingWithSentinel = maxBytes - totalBytes + if (failOnLimit) 1 else 0
            val bytesRead = read(buffer, 0, minOf(buffer.size, remainingWithSentinel))
            if (bytesRead < 0) break
            if (bytesRead == 0) continue
            if (totalBytes + bytesRead > maxBytes) {
                throw IllegalStateException("web_page_response_too_large")
            }
            output.write(buffer, 0, bytesRead)
            totalBytes += bytesRead
        }
        return output.toByteArray()
    }

    private fun ensureBeforeDeadline(deadlineNanos: Long) {
        if (deadlineNanos - System.nanoTime() <= 0L) {
            throw SocketTimeoutException("web_page_timeout")
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun <T> CancellableContinuation<T>.resumeSafely(result: Result<T>) {
        result.fold(
            onSuccess = { value ->
                tryResume(value)?.let { token -> completeResume(token) }
            },
            onFailure = { throwable ->
                tryResumeWithException(throwable)?.let { token -> completeResume(token) }
            }
        )
    }

    fun extract(url: String, html: String, maxTextChars: Int = config.maxTextChars): FetchedWebPage {
        val document = Jsoup.parse(html, url)
        document.select("script, style, noscript, nav, footer, header, aside, form").remove()

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
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_ERROR_BODY_BYTES = 500
        private const val MAX_ERROR_BODY_ENCODED_BYTES = 16 * 1024
        private const val STREAM_BUFFER_BYTES = 8 * 1024
        private const val LOCATION_HEADER = "Location"
        private const val ACCEPT_ENCODING_HEADER = "Accept-Encoding"
        private const val CONTENT_ENCODING_HEADER = "Content-Encoding"
        private const val IDENTITY_ENCODING = "identity"
        private const val GZIP_ENCODING = "gzip"
    }
}

data class WebPageExtractorConfig(
    val timeoutMillis: Long = 10_000L,
    val maxTextChars: Int = 20_000,
    val maxExcerptChars: Int = 1_000,
    val maxEncodedResponseBytes: Int = 512 * 1024,
    val maxResponseBytes: Int = 512 * 1024,
    val maxResponseChars: Int = 512 * 1024,
    val maxRedirects: Int = 5
)

private sealed interface FetchResponse {
    data class Redirect(val location: String) : FetchResponse
    data class Body(val content: String) : FetchResponse
}

private enum class WebContentEncoding {
    Identity,
    Gzip
}
