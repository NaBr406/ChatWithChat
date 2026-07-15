package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow

interface OpenAIAPI {
    fun setToken(token: String?)
    fun setAPIUrl(url: String)
    fun streamChatCompletion(request: ChatCompletionRequest, timeoutSeconds: Int): Flow<ChatCompletionChunk>
    fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int): Flow<ResponsesStreamEvent>
    suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile
    suspend fun isFileAvailable(fileId: String): Boolean
}
