package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
import kotlinx.coroutines.flow.Flow

interface GoogleAPI {
    fun streamGenerateContent(
        request: GenerateContentRequest,
        model: String,
        timeoutSeconds: Int,
        endpoint: ProviderEndpoint
    ): Flow<GenerateContentResponse>

    suspend fun uploadFile(
        filePath: String,
        fileName: String,
        mimeType: String,
        endpoint: ProviderEndpoint
    ): UploadedProviderFile

    suspend fun isFileAvailable(fileName: String, endpoint: ProviderEndpoint): Boolean
}
