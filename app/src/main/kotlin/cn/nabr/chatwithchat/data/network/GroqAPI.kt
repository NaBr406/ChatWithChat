package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.dto.groq.request.GroqChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.groq.response.GroqChatCompletionChunk
import kotlinx.coroutines.flow.Flow

interface GroqAPI {
    fun streamChatCompletion(
        request: GroqChatCompletionRequest,
        timeoutSeconds: Int,
        endpoint: ProviderEndpoint
    ): Flow<GroqChatCompletionChunk>
}
