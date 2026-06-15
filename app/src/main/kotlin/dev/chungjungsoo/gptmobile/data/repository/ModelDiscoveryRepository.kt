package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.dto.APIModel
import dev.chungjungsoo.gptmobile.data.model.ClientType

interface ModelDiscoveryRepository {
    suspend fun fetchModels(
        clientType: ClientType,
        apiUrl: String,
        token: String?
    ): List<APIModel>
}
