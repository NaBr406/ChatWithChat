package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.APIModel
import cn.nabr.chatwithchat.data.model.ClientType

interface ModelDiscoveryRepository {
    suspend fun fetchModels(
        clientType: ClientType,
        apiUrl: String,
        token: String?
    ): List<APIModel>
}
