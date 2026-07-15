package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageResponseChunk
import kotlinx.coroutines.flow.Flow

interface AnthropicAPI {
    fun setToken(token: String?)
    fun setAPIUrl(url: String)
    fun streamChatMessage(messageRequest: MessageRequest, timeoutSeconds: Int): Flow<MessageResponseChunk>
    suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile
    suspend fun isFileAvailable(fileId: String): Boolean
}
