package cn.nabr.chatwithchat.data.network

class ProviderFileUploadException(
    val providerName: String,
    val statusCode: Int,
    val responseBody: String,
    detail: String = "HTTP $statusCode: ${responseBody.take(MAX_ERROR_BODY_LENGTH)}"
) : IllegalStateException("$providerName file upload failed: $detail") {
    val isEndpointUnavailable: Boolean
        get() = statusCode == 404 || statusCode == 405 || statusCode == 501

    companion object {
        private const val MAX_ERROR_BODY_LENGTH = 500
    }
}
