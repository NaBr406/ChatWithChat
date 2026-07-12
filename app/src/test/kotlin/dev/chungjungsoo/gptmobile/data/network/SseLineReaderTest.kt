package dev.chungjungsoo.gptmobile.data.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.TooLongLineException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SseLineReaderTest {
    @Test
    fun `reads a bounded UTF-8 SSE line`() = runBlocking {
        val channel = ByteReadChannel("data: {\"value\":\"ok\"}\n")

        assertEquals("data: {\"value\":\"ok\"}", channel.readSseLineOrNull())
    }

    @Test
    fun `rejects an SSE line above the hard limit`() {
        val channel = ByteReadChannel("x".repeat(MAX_SSE_LINE_CHARS + 1) + "\n")

        assertThrows(TooLongLineException::class.java) {
            runBlocking { channel.readSseLineOrNull() }
        }
    }
}
