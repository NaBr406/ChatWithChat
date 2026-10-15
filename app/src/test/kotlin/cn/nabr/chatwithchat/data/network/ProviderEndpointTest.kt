package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import io.ktor.client.request.HttpRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun providerEndpoint_sameSavedPlatformAndChat_preservesSessionAcrossReconstruction() {
        val platform = openCodePlatform()
        val first = platform.providerEndpoint(chatId = 73)
        val restored = platform.copy(name = "Renamed", model = "another-model").providerEndpoint(chatId = 73)

        assertEquals(first.requestSessionId, restored.requestSessionId)
        assertNotEquals(first.requestSessionId, platform.providerEndpoint(chatId = 74).requestSessionId)
        assertNotEquals(first.requestSessionId, platform.copy(uid = "another-platform").providerEndpoint(chatId = 73).requestSessionId)
    }

    @Test
    fun providerEndpoint_auxiliaryCalls_haveIndependentSessions() {
        val platform = openCodePlatform()
        val endpoints = listOf(platform.providerEndpoint(), platform.providerEndpoint(0), platform.providerEndpoint(-1))

        assertEquals(3, endpoints.map { it.requestSessionId }.toSet().size)
        val request = HttpRequestBuilder().apply { applyEndpointHeaders(endpoints.first()) }
        val repeated = HttpRequestBuilder().apply { applyEndpointHeaders(endpoints.first()) }
        assertEquals(request.headers["x-opencode-session"], repeated.headers["x-opencode-session"])
    }

    @Test
    fun applyEndpointHeaders_officialHost_addsSessionAndOwnUserAgent() {
        listOf(
            "https://opencode.ai/zen/go/v1",
            " https://OPENCODE.AI/zen/go/v1/messages ",
            "https://opencode.ai/zen/v1/chat/completions"
        ).forEach { url ->
            val endpoint = openCodePlatform().copy(apiUrl = url).providerEndpoint(73)
            val request = HttpRequestBuilder().apply { applyEndpointHeaders(endpoint) }

            assertEquals(endpoint.requestSessionId, request.headers["x-opencode-session"])
            assertTrue(request.headers["User-Agent"].orEmpty().startsWith("ChatWithChat/"))
        }
    }

    @Test
    fun applyEndpointHeaders_otherHosts_doNotReceiveAutomaticHeaders() {
        listOf(
            "https://example.com/v1",
            "https://opencode.ai.example.com/zen/go/v1",
            "https://example.com/opencode.ai/zen/go/v1",
            "https://opencode.ai@example.com/zen/go/v1",
            "malformed url"
        ).forEach { url ->
            val request = HttpRequestBuilder().apply {
                applyEndpointHeaders(openCodePlatform().copy(apiUrl = url).providerEndpoint(73))
            }
            assertFalse(request.headers.contains("x-opencode-session"))
            assertFalse(request.headers.contains("User-Agent"))
        }
    }

    @Test
    fun applyEndpointHeaders_proxyTemplate_resolvesSessionWithoutChangingSavedHeaders() {
        val headers = "X-Opencode-Session: {session_id}\nUser-Agent: my-client/1.0\nX-Test: before:{session_id}:after"
        val platform = openCodePlatform().copy(apiUrl = "https://proxy.example.com/v1", customHeaders = headers)
        val endpoint = platform.providerEndpoint(73)
        val request = HttpRequestBuilder().apply { applyEndpointHeaders(endpoint) }

        assertEquals(endpoint.requestSessionId, request.headers["x-opencode-session"])
        assertEquals("my-client/1.0", request.headers["User-Agent"])
        assertEquals("before:${endpoint.requestSessionId}:after", request.headers["X-Test"])
        assertEquals(headers, platform.customHeaders)
    }

    @Test
    fun applyEndpointHeaders_explicitHeaders_arePreservedWithoutDuplicates() {
        val endpoint = openCodePlatform().copy(
            customHeaders = "X-OPENCODE-SESSION: user-session\nuser-agent: my-client/1.0"
        ).providerEndpoint(73)
        val request = HttpRequestBuilder().apply { applyEndpointHeaders(endpoint) }

        assertEquals(listOf("user-session"), request.headers.getAll("x-opencode-session"))
        assertEquals(listOf("my-client/1.0"), request.headers.getAll("User-Agent"))
    }

    @Test
    fun applyEndpointHeaders_proxySession_addsDefaultUserAgent() {
        val endpoint = openCodePlatform().copy(
            apiUrl = "https://proxy.example.com/v1",
            customHeaders = "x-opencode-session: {session_id}"
        ).providerEndpoint(73)
        val request = HttpRequestBuilder().apply { applyEndpointHeaders(endpoint) }

        assertTrue(request.headers["User-Agent"].orEmpty().startsWith("ChatWithChat/"))
    }

    private fun openCodePlatform(): PlatformV2 = PlatformV2(
        uid = "saved-platform-uuid",
        name = "My existing provider",
        compatibleType = ClientType.CUSTOM,
        apiUrl = "https://opencode.ai/zen/go/v1",
        model = "test-model"
    )
}
