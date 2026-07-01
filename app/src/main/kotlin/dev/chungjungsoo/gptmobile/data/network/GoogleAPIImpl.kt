package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable

class GoogleAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : GoogleAPI {

    private var token: String? = null
    private var apiUrl: String = "https://generativelanguage.googleapis.com"

    override fun setToken(token: String?) {
        this.token = token
    }

    override fun setAPIUrl(url: String) {
        this.apiUrl = url
    }

    override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile {
        val file = File(filePath)
        val startEndpoint = joinApiEndpoint(apiUrl, "upload/v1beta/files")
        val uploadUrl = networkClient().preparePost(startEndpoint) {
            parameter("key", token ?: "")
            contentType(ContentType.Application.Json)
            header("X-Goog-Upload-Protocol", "resumable")
            header("X-Goog-Upload-Command", "start")
            header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            header("X-Goog-Upload-Header-Content-Type", mimeType)
            setBody("""{"file":{"display_name":"$fileName"}}""")
        }.execute { response ->
            val body = response.body<String>()
            if (!response.status.isSuccess()) {
                throw ProviderFileUploadException(
                    providerName = "Google",
                    statusCode = response.status.value,
                    responseBody = body,
                    detail = parseGoogleErrorMessage(body) ?: "HTTP ${response.status.value}: ${body.take(MAX_ERROR_BODY_LENGTH)}"
                )
            }
            response.headers["x-goog-upload-url"] ?: response.headers["X-Goog-Upload-URL"]
        } ?: throw IllegalStateException("获取 Google 上传 URL 失败")

        val responseBody = networkClient().preparePost(uploadUrl) {
            header("X-Goog-Upload-Offset", "0")
            header("X-Goog-Upload-Command", "upload, finalize")
            header(HttpHeaders.ContentLength, file.length().toString())
            contentType(ContentType.parse(mimeType))
            setBody(file.readBytes())
        }.execute { response ->
            val body = response.body<String>()
            if (!response.status.isSuccess()) {
                throw ProviderFileUploadException(
                    providerName = "Google",
                    statusCode = response.status.value,
                    responseBody = body,
                    detail = parseGoogleErrorMessage(body) ?: "HTTP ${response.status.value}: ${body.take(MAX_ERROR_BODY_LENGTH)}"
                )
            }
            body
        }

        val uploadResponse = NetworkClient.json.decodeFromString<GoogleFileUploadResponse>(responseBody)
        return UploadedProviderFile(
            id = uploadResponse.file.uri ?: uploadResponse.file.name ?: "",
            mimeType = uploadResponse.file.mimeType ?: mimeType,
            name = uploadResponse.file.name,
            uri = uploadResponse.file.uri
        )
    }

    override suspend fun isFileAvailable(fileName: String): Boolean {
        val endpoint = joinApiEndpoint(apiUrl, "v1beta/$fileName")
        return try {
            networkClient().prepareGet(endpoint) {
                parameter("key", token ?: "")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    false
                } else {
                    val metadata = NetworkClient.json.decodeFromString<GoogleFileMetadata>(response.body<String>())
                    metadata.state == null || metadata.state == "ACTIVE"
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun streamGenerateContent(request: GenerateContentRequest, model: String, timeoutSeconds: Int): Flow<GenerateContentResponse> = flow {
        try {
            val endpoint = joinApiEndpoint(apiUrl, "v1beta/models/$model:streamGenerateContent")

            networkClient().preparePost(endpoint) {
                applyPlatformStreamingTimeout(timeoutSeconds)
                parameter("key", token ?: "")
                parameter("alt", "sse")
                contentType(ContentType.Application.Json)
                setBody(NetworkClient.json.encodeToString(request))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.body<String>()

                    val errorMessage = parseGoogleErrorMessage(errorBody) ?: run {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(
                        GenerateContentResponse(
                            error = ErrorDetail(
                                message = errorMessage,
                                code = response.status.value,
                                status = "ERROR"
                            )
                        )
                    )
                    return@execute
                }

                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readSseLineOrNull() ?: break

                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        try {
                            val chunk = NetworkClient.json.decodeFromString<GenerateContentResponse>(data)
                            emit(chunk)
                        } catch (_: Exception) {
                            // Skip malformed chunks
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "网络错误：无法解析主机。"
                is java.nio.channels.UnresolvedAddressException -> "网络错误：无法解析地址，请检查网络连接。"
                is java.net.ConnectException -> "网络错误：连接被拒绝，请检查 API URL。"
                is HttpRequestTimeoutException -> "请求超时。"
                is java.net.SocketTimeoutException -> "等待下一段响应时超时。"
                is javax.net.ssl.SSLException -> "网络错误：SSL/TLS 连接失败。"
                else -> e.message ?: "未知网络错误"
            }
            emit(
                GenerateContentResponse(
                    error = ErrorDetail(
                        message = errorMessage,
                        code = -1,
                        status = "NETWORK_ERROR"
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun parseGoogleErrorMessage(errorBody: String): String? {
        return try {
            NetworkClient.json.decodeFromString<List<GoogleErrorResponse>>(errorBody)
                .firstOrNull()
                ?.error
                ?.message
        } catch (_: Exception) {
            try {
                NetworkClient.json.decodeFromString<GoogleErrorResponse>(errorBody).error.message
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val MAX_ERROR_BODY_LENGTH = 500
    }
}

@Serializable
private data class GoogleErrorResponse(
    val error: GoogleError
)

@Serializable
private data class GoogleError(
    val code: Int? = null,
    val message: String,
    val status: String? = null
)

@Serializable
private data class GoogleFileUploadResponse(
    val file: GoogleFileMetadata
)

@Serializable
private data class GoogleFileMetadata(
    val name: String? = null,
    val uri: String? = null,
    @kotlinx.serialization.SerialName("mime_type")
    val mimeType: String? = null,
    val state: String? = null
)
