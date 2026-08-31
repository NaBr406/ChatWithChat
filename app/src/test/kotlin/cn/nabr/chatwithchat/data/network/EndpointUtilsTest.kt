package cn.nabr.chatwithchat.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointUtilsTest {
    @Test
    fun `join api endpoint handles trailing slashes`() {
        assertEquals(
            "https://api.example.com/v1/files",
            joinApiEndpoint("https://api.example.com/", "v1/files")
        )
    }

    @Test
    fun `join api endpoint does not duplicate leading slashes`() {
        assertEquals(
            "https://api.example.com/v1/files",
            joinApiEndpoint("https://api.example.com", "/v1/files")
        )
    }

    @Test
    fun `join api endpoint does not duplicate version path`() {
        assertEquals(
            "https://api.example.com/v1/files",
            joinApiEndpoint("https://api.example.com/v1/", "v1/files")
        )
    }

    @Test
    fun `glm endpoint uses v4 base without adding v1`() {
        val endpoint = cn.nabr.chatwithchat.data.network.ProviderEndpoint(
            token = null,
            apiUrl = "https://open.bigmodel.cn/api/paas/v4/",
            apiPathPrefix = ""
        )

        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            joinApiEndpoint(endpoint.apiUrl, endpoint.apiPath("chat/completions"))
        )
    }
}
