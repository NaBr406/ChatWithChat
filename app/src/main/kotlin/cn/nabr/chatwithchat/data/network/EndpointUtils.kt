package cn.nabr.chatwithchat.data.network

internal fun joinApiEndpoint(apiUrl: String, path: String): String {
    val normalizedBase = apiUrl.trim().ifBlank { "/" }.trimEnd('/')
    val normalizedPath = path.trimStart('/').let { endpointPath ->
        val firstSegment = endpointPath.substringBefore('/')
        if (normalizedBase.substringAfterLast('/') == firstSegment) {
            endpointPath.substringAfter('/').ifBlank { firstSegment }
        } else {
            endpointPath
        }
    }
    return "$normalizedBase/$normalizedPath"
}
