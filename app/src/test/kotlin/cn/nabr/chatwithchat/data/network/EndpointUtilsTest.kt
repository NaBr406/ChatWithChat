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
}
