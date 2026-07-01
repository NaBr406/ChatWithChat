package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.UnknownEvent
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
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

class OpenAIAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : OpenAIAPI {

    private var token: String? = null
    private var apiUrl: String = ModelConstants.OPENAI_API_URL

    override fun setToken(token: String?) {
        this.token = token
    }

    override fun setAPIUrl(url: String) {
        this.apiUrl = url
    }

    override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile {
        val endpoint = joinApiEndpoint(apiUrl, "v1/files")
        val responseBody = networkClient().preparePost(endpoint) {
            token?.let { bearerAuth(it) }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "user_data")
                        append(
                            "file",
                            File(filePath).readBytes(),
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            }
                        )
                    }
                )
            )
        }.execute { response ->
            val body = response.body<String>()
            if (!response.status.isSuccess()) {
                throw ProviderFileUploadException(
                    providerName = "OpenAI",
                    statusCode = response.status.value,
                    responseBody = body,
                    detail = parseOpenAIErrorMessage(body) ?: "HTTP ${response.status.value}: ${body.take(MAX_ERROR_BODY_LENGTH)}"
                )
            }
            body
        }

        val uploadResponse = NetworkClient.openAIJson.decodeFromString<OpenAIFileResponse>(responseBody)
        return UploadedProviderFile(
            id = uploadResponse.id,
            mimeType = uploadResponse.mimeType ?: mimeType,
            name = uploadResponse.filename
        )
    }

    override suspend fun isFileAvailable(fileId: String): Boolean {
        val endpoint = joinApiEndpoint(apiUrl, "v1/files/$fileId")
        return try {
            networkClient().prepareGet(endpoint) {
                token?.let { bearerAuth(it) }
            }.execute { response ->
                response.status.isSuccess()
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun streamChatCompletion(request: ChatCompletionRequest, timeoutSeconds: Int): Flow<ChatCompletionChunk> = flow {
        try {
            val endpoint = joinApiEndpoint(apiUrl, "v1/chat/completions")

            networkClient().preparePost(endpoint) {
                applyPlatformStreamingTimeout(timeoutSeconds)
                contentType(ContentType.Application.Json)
                setBody(NetworkClient.openAIJson.encodeToString(request))
                accept(ContentType.Text.EventStream)
                token?.let { bearerAuth(it) }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.body<String>()

                    val errorMessage = parseOpenAIErrorMessage(errorBody) ?: run {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(
                        ChatCompletionChunk(
                            error = ErrorDetail(
                                message = errorMessage,
                                type = "http_error",
                                code = response.status.value.toString()
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
                        // OpenAI sends "[DONE]" as final message
                        if (data == "[DONE]") break

                        try {
                            val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(data)
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
                ChatCompletionChunk(
                    error = ErrorDetail(
                        message = errorMessage,
                        type = "network_error"
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int): Flow<ResponsesStreamEvent> = flow {
        try {
            val endpoint = joinApiEndpoint(apiUrl, "v1/responses")

            networkClient().preparePost(endpoint) {
                applyPlatformStreamingTimeout(timeoutSeconds)
                contentType(ContentType.Application.Json)
                setBody(NetworkClient.openAIJson.encodeToString(request))
                accept(ContentType.Text.EventStream)
                token?.let { bearerAuth(it) }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.body<String>()

                    val errorMessage = parseOpenAIErrorMessage(errorBody) ?: run {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(ResponseErrorEvent(message = errorMessage, code = response.status.value.toString()))
                    return@execute
                }

                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readSseLineOrNull() ?: break

                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        try {
                            val streamEvent = NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(data)
                            emit(streamEvent)
                        } catch (_: Exception) {
                            emit(UnknownEvent)
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
                ResponseErrorEvent(
                    message = errorMessage,
                    code = "network_error"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun parseOpenAIErrorMessage(errorBody: String): String? = try {
        NetworkClient.openAIJson.decodeFromString<OpenAIErrorResponse>(errorBody).error.message
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val MAX_ERROR_BODY_LENGTH = 500
    }
}

@Serializable
private data class OpenAIErrorResponse(
    val error: OpenAIError
)

@Serializable
private data class OpenAIError(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null
)

@Serializable
private data class OpenAIFileResponse(
    val id: String,
    val filename: String? = null,
    @kotlinx.serialization.SerialName("mime_type")
    val mimeType: String? = null
)
