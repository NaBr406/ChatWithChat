package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelV2
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.AvailableChatModel
import dev.chungjungsoo.gptmobile.data.model.LastSelectedModel
import dev.chungjungsoo.gptmobile.data.model.ModelRefreshResult
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.tool.ToolCallingMode
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchMode

interface SettingRepository {
    suspend fun fetchPlatforms(): List<Platform>
    suspend fun fetchPlatformV2s(): List<PlatformV2>
    suspend fun fetchPlatformModels(): List<PlatformModelV2>
    suspend fun fetchPlatformModels(platformUid: String): List<PlatformModelV2>
    suspend fun fetchEnabledChatModels(): List<AvailableChatModel>
    suspend fun resolveDefaultChatModel(): AvailableChatModel?
    suspend fun fetchThemes(): ThemeSetting
    suspend fun fetchLastSelectedModel(): LastSelectedModel?
    suspend fun fetchMemoryEnabled(): Boolean
    suspend fun fetchMemoryMaintenanceNotificationsEnabled(): Boolean
    suspend fun fetchToolCallingMode(): ToolCallingMode
    suspend fun fetchWebSearchMode(): WebSearchMode
    suspend fun fetchWebSearchSearxngBaseUrl(): String
    suspend fun migrateToPlatformV2()
    suspend fun updatePlatforms(platforms: List<Platform>)
    suspend fun updateThemes(themeSetting: ThemeSetting)
    suspend fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode = ReasoningMode.AUTO)
    suspend fun updateMemoryEnabled(enabled: Boolean)
    suspend fun updateMemoryMaintenanceNotificationsEnabled(enabled: Boolean)
    suspend fun updateToolCallingMode(mode: ToolCallingMode)
    suspend fun updateWebSearchMode(mode: WebSearchMode)
    suspend fun updateWebSearchSearxngBaseUrl(baseUrl: String)
    suspend fun refreshPlatformModels(platformUid: String): ModelRefreshResult
    suspend fun updatePlatformModelEnabled(platformUid: String, modelId: String, enabled: Boolean)
    suspend fun setPlatformDefaultModel(platformUid: String, modelId: String)

    // PlatformV2 CRUD operations
    suspend fun addPlatformV2(platform: PlatformV2)
    suspend fun updatePlatformV2(platform: PlatformV2)
    suspend fun deletePlatformV2(platform: PlatformV2)
    suspend fun getPlatformV2ById(id: Int): PlatformV2?
}
