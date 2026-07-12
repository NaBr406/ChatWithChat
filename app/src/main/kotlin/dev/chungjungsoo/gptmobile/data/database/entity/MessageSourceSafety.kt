package dev.chungjungsoo.gptmobile.data.database.entity

import java.net.URI
import java.util.Locale

sealed class SafeMessageSourceTarget {
    data class PublicUrl(val url: String) : SafeMessageSourceTarget()

    data class LocalApp(
        val navigationTarget: AppSourceNavigationTarget,
        val entityId: String
    ) : SafeMessageSourceTarget()
}

fun String.safeHttpUrlOrNull(): String? {
    val candidate = trim()
    if (candidate.isBlank() || candidate.length > MAX_SAFE_SOURCE_URL_CHARS || candidate.any { char -> char.isISOControl() }) {
        return null
    }

    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.US)
    if (uri.isOpaque || scheme !in SAFE_PUBLIC_URL_SCHEMES || uri.host.isNullOrBlank() || uri.userInfo != null) {
        return null
    }
    return candidate
}

fun String.isSafeLocalEntityId(): Boolean {
    val candidate = trim()
    return candidate.length in 1..MAX_SAFE_LOCAL_ENTITY_ID_CHARS && SAFE_LOCAL_ENTITY_ID.matches(candidate)
}

fun MessageSourceMetadata.safeNavigationTarget(): SafeMessageSourceTarget? = when (sourceType) {
    MessageSourceType.PUBLIC_URL -> url.safeHttpUrlOrNull()?.let(SafeMessageSourceTarget::PublicUrl)
    MessageSourceType.LOCAL_APP -> {
        val entityId = localEntityId?.trim()?.takeIf { value -> value.isSafeLocalEntityId() }
        val target = appNavigationTarget
        if (url.isNotBlank() || entityId == null || target == null) {
            null
        } else {
            SafeMessageSourceTarget.LocalApp(target, entityId)
        }
    }
}

fun MessageSourceMetadata.isSafeSource(): Boolean = safeNavigationTarget() != null

fun MessageSourceMetadata.safeDedupeKey(): String? = when (val target = safeNavigationTarget()) {
    is SafeMessageSourceTarget.PublicUrl -> "public:${target.url.normalizedPublicUrlKey()}"
    is SafeMessageSourceTarget.LocalApp -> "local:${target.navigationTarget}:${target.entityId}"
    null -> null
}

private fun String.normalizedPublicUrlKey(): String {
    val uri = URI(this)
    return buildString {
        append(uri.scheme.lowercase(Locale.US))
        append("://")
        append(uri.host.lowercase(Locale.US))
        if (uri.port >= 0) append(":${uri.port}")
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { query -> append("?$query") }
        uri.rawFragment?.let { fragment -> append("#$fragment") }
    }
}

private val SAFE_PUBLIC_URL_SCHEMES = setOf("http", "https")
private val SAFE_LOCAL_ENTITY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]*")
private const val MAX_SAFE_SOURCE_URL_CHARS = 2_048
private const val MAX_SAFE_LOCAL_ENTITY_ID_CHARS = 256
