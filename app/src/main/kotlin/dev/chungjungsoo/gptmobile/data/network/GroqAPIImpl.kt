package dev.chungjungsoo.gptmobile.data.network

import android.util.Log
import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqErrorDetail
import dev.chungjungsoo.gptmobile.util.applyPlatformStreamingTimeout
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable

class GroqAPIImpl @Inject constructor(
    private val networkClient: NetworkClient
) : GroqAPI {

    override fun streamChatCompletion(
        request: GroqChatCompletionRequest,
        timeoutSeconds: Int,
        token: String?,
        apiUrl: String
    ): Flow<GroqChatCompletionChunk> = flow {
        try {
            val resolvedApiUrl = apiUrl.ifBlank { ModelConstants.GROQ_API_URL }
            val endpoint = if (resolvedApiUrl.endsWith("/")) "${resolvedApiUrl}v1/chat/completions" else "$resolvedApiUrl/v1/chat/completions"

            networkClient().preparePost(endpoint) {
                applyPlatformStreamingTimeout(timeoutSeconds)
                contentType(ContentType.Application.Json)
                setBody(NetworkClient.openAIJson.encodeToString(request))
                accept(if (request.stream) ContentType.Text.EventStream else ContentType.Application.Json)
                token?.let { bearerAuth(it) }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.body<String>()
                    val errorMessage = try {
                        val errorResponse = NetworkClient.openAIJson.decodeFromString<GroqErrorResponse>(errorBody)
                        errorResponse.error.message
                    } catch (_: Exception) {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(
                        GroqChatCompletionChunk(
                            error = GroqErrorDetail(
                                message = errorMessage,
                                type = "http_error",
                                code = response.status.value.toString()
                            )
                        )
                    )
                    return@execute
                }

                if (!request.stream) {
                    emit(NetworkClient.openAIJson.decodeFromString<GroqChatCompletionChunk>(response.body()))
                    return@execute
                }

                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readSseLineOrNull() ?: break
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    try {
                        emit(NetworkClient.openAIJson.decodeFromString<GroqChatCompletionChunk>(data))
                    } catch (e: Exception) {
                        Log.w("GroqAPI", "Skipping malformed Groq chunk: $data", e)
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
                GroqChatCompletionChunk(
                    error = GroqErrorDetail(
                        message = errorMessage,
                        type = "network_error"
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)
}

@Serializable
private data class GroqErrorResponse(
    val error: GroqErrorPayload
)

@Serializable
private data class GroqErrorPayload(
    val message: String,
    val type: String? = null,
    val code: String? = null
)
