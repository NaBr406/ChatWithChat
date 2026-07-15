package cn.nabr.chatwithchat.data.websearch

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

data class WebFetchRequestPolicy(
    val blockedDomains: Set<String> = emptySet(),
    val allowPrivateNetwork: Boolean = false
)

fun interface WebFetchHostResolver {
    fun resolve(host: String): List<InetAddress>
}

internal class WebFetchUrlValidator {
    fun validate(rawUrl: String, policy: WebFetchRequestPolicy): URI {
        val uri = runCatching { URI(rawUrl.trim()) }
            .getOrElse { throw IllegalArgumentException("invalid_url", it) }
        if (!uri.isAbsolute || uri.scheme?.lowercase(Locale.US) !in HTTP_SCHEMES) {
            throw IllegalArgumentException("invalid_url_scheme")
        }
        if (uri.rawUserInfo != null) {
            throw IllegalArgumentException("invalid_url_user_info")
        }

        val host = uri.host
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("invalid_url_host")
        val asciiHost = host.normalizedAsciiHost()

        asciiHost.blockedDomain(policy.blockedDomains)?.let { blockedDomain ->
            throw IllegalArgumentException("blocked_domain:$blockedDomain")
        }
        if (asciiHost == "localhost" || asciiHost.endsWith(".localhost")) {
            if (!policy.allowPrivateNetwork) throw IllegalArgumentException("private_url_rejected")
        }

        asciiHost.parseIpLiteralOrNull()?.let { address ->
            validateResolvedAddresses(listOf(address), policy)
        }

        return uri
    }

    fun validateResolvedAddresses(addresses: List<InetAddress>, policy: WebFetchRequestPolicy) {
        if (addresses.isEmpty()) {
            throw IllegalArgumentException("url_host_resolution_failed")
        }
        if (!policy.allowPrivateNetwork && addresses.any { !it.isPubliclyRoutable() }) {
            throw IllegalArgumentException("private_url_rejected")
        }
    }

    private fun String.normalizedAsciiHost(): String = if (contains(':')) {
        lowercase(Locale.US)
    } else {
        runCatching { IDN.toASCII(this, IDN.USE_STD3_ASCII_RULES) }
            .getOrElse { throw IllegalArgumentException("invalid_url_host", it) }
            .lowercase(Locale.US)
    }

    private fun String.parseIpLiteralOrNull(): InetAddress? {
        val looksLikeIpv6 = contains(':')
        val looksLikeIpv4 = all { character -> character.isDigit() || character == '.' }
        if (!looksLikeIpv6 && !looksLikeIpv4) return null
        return runCatching { InetAddress.getByName(this) }
            .getOrElse { throw IllegalArgumentException("invalid_url_host", it) }
    }

    private fun String.blockedDomain(blockedDomains: Set<String>): String? = blockedDomains
        .mapNotNull { blockedDomain ->
            val normalized = blockedDomain.trim().trimStart('.').trimEnd('.')
            if (normalized.isBlank()) {
                null
            } else {
                runCatching { IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES) }
                    .getOrNull()
                    ?.lowercase(Locale.US)
            }
        }
        .firstOrNull { blockedDomain -> this == blockedDomain || endsWith(".$blockedDomain") }

    private fun InetAddress.isPubliclyRoutable(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return false
        }

        val bytes = address.map { it.toInt() and 0xff }
        return when (bytes.size) {
            IPV4_BYTE_COUNT -> bytes.isPublicIpv4()
            IPV6_BYTE_COUNT -> bytes.isPublicIpv6()
            else -> false
        }
    }

    private fun List<Int>.isPublicIpv4(): Boolean {
        val first = this[0]
        val second = this[1]
        val third = this[2]
        return when {
            first == 0 || first == 10 || first == 127 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && third == 0 -> false
            first == 192 && second == 0 && third == 2 -> false
            first == 192 && second == 168 -> false
            first == 192 && second == 88 && third == 99 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun List<Int>.isPublicIpv6(): Boolean {
        val isGlobalUnicast = this[0] in 0x20..0x3f
        if (!isGlobalUnicast) return false

        val isIetfProtocolAssignment = this[0] == 0x20 && this[1] == 0x01 && this[2] in 0x00..0x01
        if (isIetfProtocolAssignment && !isGloballyReachableIetfAssignment()) return false

        val isDocumentation = this[0] == 0x20 && this[1] == 0x01 && this[2] == 0x0d && this[3] == 0xb8
        val isSixToFour = this[0] == 0x20 && this[1] == 0x02
        val isExtendedDocumentation =
            this[0] == 0x3f && this[1] == 0xff && this[2] in 0x00..0x0f
        return !isDocumentation &&
            !isSixToFour &&
            !isExtendedDocumentation
    }

    private fun List<Int>.isGloballyReachableIetfAssignment(): Boolean {
        val isProtocolAnycast =
            this[2] == 0x00 &&
                this[3] == 0x01 &&
                subList(4, 15).all { it == 0 } &&
                this[15] in 0x01..0x03
        val isAmt = this[2] == 0x00 && this[3] == 0x03
        val isAs112 =
            this[2] == 0x00 && this[3] == 0x04 && this[4] == 0x01 && this[5] == 0x12
        return isProtocolAnycast || isAmt || isAs112
    }

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
        const val IPV4_BYTE_COUNT = 4
        const val IPV6_BYTE_COUNT = 16
    }
}

internal val systemWebFetchHostResolver = WebFetchHostResolver { host ->
    InetAddress.getAllByName(host).toList()
}
