package dev.chungjungsoo.gptmobile.data.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedReadChannelException
import io.ktor.utils.io.readLineStrict
import java.io.EOFException

suspend fun ByteReadChannel.readSseLineOrNull(): String? = try {
    readLineStrict(MAX_SSE_LINE_CHARS.toLong())
} catch (_: EOFException) {
    null
} catch (_: ClosedReadChannelException) {
    null
}

internal const val MAX_SSE_LINE_CHARS = 128 * 1024
