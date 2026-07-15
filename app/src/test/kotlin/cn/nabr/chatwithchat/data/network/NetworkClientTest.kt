package cn.nabr.chatwithchat.data.network

import io.ktor.client.plugins.logging.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkClientTest {

    @Test
    fun `network logging avoids request body logging`() {
        assertEquals(LogLevel.HEADERS, NetworkClient.resolveNetworkLogLevel())
    }
}
