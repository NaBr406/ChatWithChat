package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
import kotlinx.coroutines.flow.Flow

interface GoogleAPI {
    fun setToken(token: String?)
    fun setAPIUrl(url: String)
    fun streamGenerateContent(request: GenerateContentRequest, model: String, timeoutSeconds: Int): Flow<GenerateContentResponse>
    suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile
    suspend fun isFileAvailable(fileName: String): Boolean
}
