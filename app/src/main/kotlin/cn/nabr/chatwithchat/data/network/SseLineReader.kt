package cn.nabr.chatwithchat.data.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedReadChannelException
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readByte
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

// Decode only after collecting a complete line so a UTF-8 code point split across
// CIO buffers cannot be converted into replacement characters.
suspend fun ByteReadChannel.readSseLineOrNull(): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val nextByte = try {
            readByte()
        } catch (_: EOFException) {
            return line.decodeSseLineAtEofOrNull()
        } catch (_: ClosedReadChannelException) {
            return line.decodeSseLineAtEofOrNull()
        }

        if (nextByte == NEWLINE_BYTE) {
            return line.decodeSseLine()
        }

        line.write(nextByte.toInt())
        if (line.size() > MAX_SSE_LINE_BYTES) {
            line.throwTooLongLine()
        }
    }
}

internal const val MAX_SSE_LINE_CHARS = 128 * 1024

private const val MAX_SSE_LINE_BYTES = MAX_SSE_LINE_CHARS * 4
private const val NEWLINE_BYTE: Byte = 10
private const val SAMPLE_CHARS = 160

private fun ByteArrayOutputStream.decodeSseLineAtEofOrNull(): String? =
    if (size() == 0) null else decodeSseLine()

private fun ByteArrayOutputStream.decodeSseLine(): String {
    val bytes = toByteArray()
    val contentLength = if (bytes.isNotEmpty() && bytes.last() == CARRIAGE_RETURN_BYTE) bytes.size - 1 else bytes.size
    val line = String(bytes, 0, contentLength, StandardCharsets.UTF_8)
    if (line.length > MAX_SSE_LINE_CHARS) {
        throwTooLongLine()
    }
    return line
}

// A body that never breaks a line inside the cap is almost never an event stream, so carry the
// leading characters into the message. The bare limit is reported verbatim in the chat bubble and
// on its own it hides which of the real causes hit: an HTML page from a wrong API URL, a JSON
// error blob, or a provider that answered without streaming.
private fun ByteArrayOutputStream.throwTooLongLine(): Nothing {
    val bytes = toByteArray()
    val sample = String(bytes, 0, minOf(bytes.size, SAMPLE_CHARS * 4), StandardCharsets.UTF_8)
        .take(SAMPLE_CHARS)
        .replace(WHITESPACE_RUN, " ")
        .trim()
    throw TooLongLineException("响应不是有效的 SSE 流：单行超过 $MAX_SSE_LINE_CHARS 字符上限。开头内容：$sample")
}

private val WHITESPACE_RUN = Regex("\\s+")

private const val CARRIAGE_RETURN_BYTE: Byte = 13
