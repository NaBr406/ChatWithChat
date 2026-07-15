package cn.nabr.chatwithchat.data.websearch

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

internal class PinnedWebFetchDns(
    private val hostResolver: WebFetchHostResolver,
    private val requestPolicy: WebFetchRequestPolicy,
    private val urlValidator: WebFetchUrlValidator
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = try {
            hostResolver.resolve(hostname)
        } catch (throwable: Exception) {
            throw UnknownHostException("url_host_resolution_failed").apply { initCause(throwable) }
        }

        try {
            urlValidator.validateResolvedAddresses(addresses, requestPolicy)
        } catch (throwable: IllegalArgumentException) {
            throw UnknownHostException(throwable.message ?: "url_host_resolution_failed").apply { initCause(throwable) }
        }
        return addresses
    }
}
