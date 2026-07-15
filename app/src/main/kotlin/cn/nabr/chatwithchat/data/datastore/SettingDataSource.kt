package cn.nabr.chatwithchat.data.datastore

import cn.nabr.chatwithchat.data.model.ApiType
import cn.nabr.chatwithchat.data.model.DynamicTheme
import cn.nabr.chatwithchat.data.model.ThemeMode

interface SettingDataSource {
    suspend fun updateDynamicTheme(theme: DynamicTheme)
    suspend fun updateThemeMode(themeMode: ThemeMode)
    suspend fun updateStatus(apiType: ApiType, status: Boolean)
    suspend fun updateAPIUrl(apiType: ApiType, url: String)
    suspend fun updateToken(apiType: ApiType, token: String)
    suspend fun updateModel(apiType: ApiType, model: String)
    suspend fun updateTemperature(apiType: ApiType, temperature: Float)
    suspend fun updateTopP(apiType: ApiType, topP: Float)
    suspend fun updateSystemPrompt(apiType: ApiType, prompt: String)
    suspend fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: String)
    suspend fun updateMemoryEnabled(enabled: Boolean)
    suspend fun updateMemoryMaintenanceNotificationsEnabled(enabled: Boolean)
    suspend fun updateToolCallingMode(mode: String)
    suspend fun updateToolEnabled(toolName: String, enabled: Boolean)
    suspend fun updateWebSearchMode(mode: String)
    suspend fun updateWebSearchSearxngBaseUrl(baseUrl: String)
    suspend fun getDynamicTheme(): DynamicTheme?
    suspend fun getThemeMode(): ThemeMode?
    suspend fun getStatus(apiType: ApiType): Boolean?
    suspend fun getAPIUrl(apiType: ApiType): String?
    suspend fun getToken(apiType: ApiType): String?
    suspend fun getModel(apiType: ApiType): String?
    suspend fun getTemperature(apiType: ApiType): Float?
    suspend fun getTopP(apiType: ApiType): Float?
    suspend fun getSystemPrompt(apiType: ApiType): String?
    suspend fun getLastSelectedModelPlatformUid(): String?
    suspend fun getLastSelectedModel(): String?
    suspend fun getLastSelectedReasoningMode(): String?
    suspend fun getMemoryEnabled(): Boolean?
    suspend fun getMemoryMaintenanceNotificationsEnabled(): Boolean?
    suspend fun getToolCallingMode(): String?
    suspend fun getEnabledToolNames(): Set<String>
    suspend fun getDisabledToolNames(): Set<String>
    suspend fun getWebSearchMode(): String?
    suspend fun getWebSearchSearxngBaseUrl(): String?
}
