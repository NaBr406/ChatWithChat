package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import io.ktor.client.request.HttpRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEndpointTest {
    @Test
    fun chatUrl_baseUrl_appendsProviderPath() {
        val endpoint = ProviderEndpoint(null, "https://api.example.com/v1/")
        assertEquals("https://api.example.com/v1/chat/completions", endpoint.chatUrl("v1/chat/completions"))
    }

    @Test
    fun chatUrl_fullUrl_preservesPathAndQuery() {
        val url = "https://api.example.com/custom/chat?api-version=2026-01-01"
        val endpoint = ProviderEndpoint(null, " $url ", isFullApiUrl = true)
        assertEquals(url, endpoint.chatUrl("v1/chat/completions"))
    }

    @Test
    fun chatUrl_fullUrl_supportsAllChatProtocols() {
        listOf(
            "v1/chat/completions",
            "v1/messages",
            "v1/responses",
            "v1beta/models/gemini:streamGenerateContent"
        ).forEach { path ->
            val url = "https://api.example.com/$path"
            assertEquals(url, ProviderEndpoint(null, url, isFullApiUrl = true).chatUrl(path))
        }
    }

    @Test
    fun baseApiUrl_fullUrl_resolvesAuxiliaryEndpoints() {
        val expectedBases = mapOf(
            "v1/chat/completions" to "v1",
            "v1/responses" to "v1",
            "v1/messages" to "v1",
            "api/paas/v4/chat/completions" to "api/paas/v4",
            "v1beta/models/gemini:streamGenerateContent" to ""
        )
        expectedBases.forEach { (path, basePath) ->
            val endpoint = ProviderEndpoint(null, "https://api.example.com/$path?key=secret", isFullApiUrl = true)
            val expected = "https://api.example.com" + if (basePath.isEmpty()) "" else "/$basePath"
            assertEquals(expected, endpoint.baseApiUrl())
        }
    }

    @Test
    fun baseApiUrl_baseMode_preservesExistingConfiguration() {
        val url = "https://api.example.com/proxy/"
        assertEquals(url, ProviderEndpoint(null, url).baseApiUrl())
    }

    @Test
    fun providerEndpoint_platform_passesFullUrlSetting() {
        val platform = PlatformV2(
            name = "Custom",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://api.example.com/v1/chat/completions",
            model = "test-model",
            isFullApiUrl = true
        )
        assertTrue(platform.providerEndpoint().isFullApiUrl)
        assertEquals(platform.apiUrl, platform.providerEndpoint().chatUrl("v1/chat/completions"))
    }
    @Test
    fun applyCustomHeaders_parsesLinesAndSkipsManagedHeaders() {
        val request = HttpRequestBuilder()
        request.applyCustomHeaders(
            """
            User-Agent: Mozilla/5.0
            X-Test: enabled
            Authorization: should-not-override
            content-type: text/plain
            malformed
            """.trimIndent()
        )

        assertEquals("Mozilla/5.0", request.headers["User-Agent"])
        assertEquals("enabled", request.headers["X-Test"])
        assertTrue(request.headers["Authorization"].isNullOrEmpty())
        assertTrue(request.headers["Content-Type"].isNullOrEmpty())
    }
}
