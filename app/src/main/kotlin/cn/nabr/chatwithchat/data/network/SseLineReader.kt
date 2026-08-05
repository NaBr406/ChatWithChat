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
            throw TooLongLineException("SSE line exceeds $MAX_SSE_LINE_CHARS characters")
        }
    }
}

internal const val MAX_SSE_LINE_CHARS = 128 * 1024

private const val MAX_SSE_LINE_BYTES = MAX_SSE_LINE_CHARS * 4
private const val NEWLINE_BYTE: Byte = 10

private fun ByteArrayOutputStream.decodeSseLineAtEofOrNull(): String? =
    if (size() == 0) null else decodeSseLine()

private fun ByteArrayOutputStream.decodeSseLine(): String {
    val bytes = toByteArray()
    val contentLength = if (bytes.isNotEmpty() && bytes.last() == CARRIAGE_RETURN_BYTE) bytes.size - 1 else bytes.size
    val line = String(bytes, 0, contentLength, StandardCharsets.UTF_8)
    if (line.length > MAX_SSE_LINE_CHARS) {
        throw TooLongLineException("SSE line exceeds $MAX_SSE_LINE_CHARS characters")
    }
    return line
}

private const val CARRIAGE_RETURN_BYTE: Byte = 13
