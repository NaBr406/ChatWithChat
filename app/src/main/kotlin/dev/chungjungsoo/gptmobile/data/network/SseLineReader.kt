package dev.chungjungsoo.gptmobile.data.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedReadChannelException
import io.ktor.utils.io.readLine
import java.io.EOFException

suspend fun ByteReadChannel.readSseLineOrNull(): String? = try {
    readLine()
} catch (_: EOFException) {
    null
} catch (_: ClosedReadChannelException) {
    null
}
