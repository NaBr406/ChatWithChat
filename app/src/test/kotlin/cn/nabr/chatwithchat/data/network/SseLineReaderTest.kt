package cn.nabr.chatwithchat.data.network

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.writeFully
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SseLineReaderTest {
    @Test
    fun `reads a bounded UTF-8 SSE line`() = runBlocking {
        val channel = ByteReadChannel("data: {\"value\":\"ok\"}\n")

        assertEquals("data: {\"value\":\"ok\"}", channel.readSseLineOrNull())
    }

    @Test
    fun `preserves UTF-8 when a character is split across channel writes`() = runBlocking {
        val expected = "data: {\"value\":\"大佬，你好\"}"
        val bytes = (expected + "\n").toByteArray(StandardCharsets.UTF_8)
        val split = ("data: {\"value\":\"大".toByteArray(StandardCharsets.UTF_8).size) + 1
        val channel = ByteChannel(autoFlush = true)

        val writer = launch {
            channel.writeFully(bytes, 0, split)
            delay(100)
            channel.writeFully(bytes, split, bytes.size)
            channel.close()
        }

        assertEquals(expected, channel.readSseLineOrNull())
        writer.join()
    }

    @Test
    fun `keeps empty lines and handles CRLF`() = runBlocking {
        val channel = ByteReadChannel("\r\n\n")

        assertEquals("", channel.readSseLineOrNull())
        assertEquals("", channel.readSseLineOrNull())
        assertNull(channel.readSseLineOrNull())
    }

    @Test
    fun `rejects an SSE line above the hard limit`() {
        val channel = ByteReadChannel("x".repeat(MAX_SSE_LINE_CHARS + 1) + "\n")

        assertThrows(TooLongLineException::class.java) {
            runBlocking { channel.readSseLineOrNull() }
        }
    }
}
