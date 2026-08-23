package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesCompactRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesCompactResult
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow

interface OpenAIAPI {
    fun streamChatCompletion(
        request: ChatCompletionRequest,
        timeoutSeconds: Int,
        endpoint: ProviderEndpoint
    ): Flow<ChatCompletionChunk>

    fun streamResponses(
        request: ResponsesRequest,
        timeoutSeconds: Int,
        endpoint: ProviderEndpoint
    ): Flow<ResponsesStreamEvent>

    suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        endpoint: ProviderEndpoint
    ): UploadedProviderFile

    suspend fun isFileAvailable(fileId: String, endpoint: ProviderEndpoint): Boolean

    /**
     * Non-streaming `POST /v1/responses/compact`. Only for initialising native compaction state for a
     * range too large to submit as one `/v1/responses` input; the normal path is server-side
     * compaction via `context_management`.
     *
     * Returns the canonical output window with every output item preserved as raw JSON.
     */
    suspend fun compactResponses(
        request: ResponsesCompactRequest,
        timeoutSeconds: Int,
        endpoint: ProviderEndpoint
    ): ResponsesCompactResult
}
