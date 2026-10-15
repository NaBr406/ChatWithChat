package cn.nabr.chatwithchat.data.network

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.charsets.TooLongLineException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException

internal fun Throwable.isChatNetworkFailure(): Boolean =
    this !is CancellationException &&
        this !is TooLongLineException &&
        this !is SSLHandshakeException &&
        this !is SSLPeerUnverifiedException &&
        (this is IOException || this is HttpRequestTimeoutException || this is UnresolvedAddressException)

internal fun Throwable.throwIfChatNetworkFailure() {
    if (this is CancellationException || isChatNetworkFailure()) throw this
}
