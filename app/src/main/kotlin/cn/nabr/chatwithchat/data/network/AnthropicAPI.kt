package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageResponseChunk
import kotlinx.coroutines.flow.Flow

interface AnthropicAPI {
    fun streamChatMessage(
        messageRequest: MessageRequest,
        timeoutSeconds: Int,
        endpoint: ProviderEndpoint
    ): Flow<MessageResponseChunk>

    suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        endpoint: ProviderEndpoint
    ): UploadedProviderFile

    suspend fun isFileAvailable(fileId: String, endpoint: ProviderEndpoint): Boolean
}
