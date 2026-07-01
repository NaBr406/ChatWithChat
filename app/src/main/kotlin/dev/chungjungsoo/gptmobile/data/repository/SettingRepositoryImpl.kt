package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelRefreshStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.APIModel
import dev.chungjungsoo.gptmobile.data.datastore.SettingDataSource
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.ApiType
import dev.chungjungsoo.gptmobile.data.model.AvailableChatModel
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.LastSelectedModel
import dev.chungjungsoo.gptmobile.data.model.ModelRefreshResult
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.model.defaultReasoningMode
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchMode
import javax.inject.Inject

class SettingRepositoryImpl @Inject constructor(
    private val settingDataSource: SettingDataSource,
    private val platformV2Dao: PlatformV2Dao,
    private val platformModelV2Dao: PlatformModelV2Dao,
    private val chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
    private val modelDiscoveryRepository: ModelDiscoveryRepository
) : SettingRepository {

    override suspend fun fetchPlatforms(): List<Platform> = ApiType.entries.map { apiType ->
        val status = settingDataSource.getStatus(apiType)
        val apiUrl = when (apiType) {
            ApiType.OPENAI -> settingDataSource.getAPIUrl(apiType) ?: ModelConstants.OPENAI_API_URL
            ApiType.ANTHROPIC -> settingDataSource.getAPIUrl(apiType) ?: ModelConstants.ANTHROPIC_API_URL
            ApiType.GOOGLE -> settingDataSource.getAPIUrl(apiType) ?: ModelConstants.GOOGLE_API_URL
            ApiType.GROQ -> settingDataSource.getAPIUrl(apiType) ?: ModelConstants.GROQ_API_URL
            ApiType.OLLAMA -> settingDataSource.getAPIUrl(apiType) ?: ""
        }
        val token = settingDataSource.getToken(apiType)
        val model = settingDataSource.getModel(apiType)
        val temperature = settingDataSource.getTemperature(apiType)
        val topP = settingDataSource.getTopP(apiType)
        val systemPrompt = settingDataSource.getSystemPrompt(apiType)

        Platform(
            name = apiType,
            enabled = status == true,
            apiUrl = apiUrl,
            token = token,
            model = model,
            temperature = temperature,
            topP = topP,
            systemPrompt = systemPrompt
        )
    }

    override suspend fun fetchPlatformV2s(): List<PlatformV2> = platformV2Dao.getPlatforms()

    override suspend fun fetchPlatformModels(): List<PlatformModelV2> = platformModelV2Dao.getModels()

    override suspend fun fetchPlatformModels(platformUid: String): List<PlatformModelV2> =
        platformModelV2Dao.getModelsForPlatform(platformUid)

    override suspend fun fetchEnabledChatModels(): List<AvailableChatModel> {
        val enabledPlatforms = platformV2Dao.getPlatforms()
            .filter { it.enabled }
        val modelsByPlatformUid = platformModelV2Dao.getModels()
            .filter { it.enabled }
            .groupBy { it.platformUid }

        return enabledPlatforms.flatMap { platform ->
            modelsByPlatformUid[platform.uid].orEmpty().map { model ->
                AvailableChatModel(platform, model)
            }
        }
    }

    override suspend fun resolveDefaultChatModel(): AvailableChatModel? {
        val enabledModels = fetchEnabledChatModels()
        if (enabledModels.isEmpty()) return null

        fetchLastSelectedModel()?.let { lastSelectedModel ->
            enabledModels.firstOrNull { model ->
                model.platformUid == lastSelectedModel.platformUid && model.modelId == lastSelectedModel.model
            }?.let { return it }
        }

        val modelsByPlatformUid = enabledModels.groupBy { it.platformUid }
        platformV2Dao.getPlatforms()
            .filter { it.enabled }
            .forEach { platform ->
                modelsByPlatformUid[platform.uid]
                    ?.let { models -> models.firstOrNull { it.model.isDefault } ?: models.firstOrNull() }
                    ?.let { return it }
            }

        return enabledModels.first()
    }

    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting(
        dynamicTheme = settingDataSource.getDynamicTheme() ?: DynamicTheme.OFF,
        themeMode = settingDataSource.getThemeMode() ?: ThemeMode.SYSTEM
    )

    override suspend fun fetchLastSelectedModel(): LastSelectedModel? {
        val platformUid = settingDataSource.getLastSelectedModelPlatformUid()?.takeIf { it.isNotBlank() } ?: return null
        val model = settingDataSource.getLastSelectedModel()?.takeIf { it.isNotBlank() } ?: return null
        val storedReasoningMode = settingDataSource.getLastSelectedReasoningMode()
        val reasoningMode = if (storedReasoningMode.isNullOrBlank()) {
            platformV2Dao.getPlatforms()
                .firstOrNull { it.uid == platformUid }
                ?.defaultReasoningMode()
                ?: ReasoningMode.AUTO
        } else {
            ReasoningMode.fromStorageValue(storedReasoningMode)
        }

        return LastSelectedModel(platformUid = platformUid, model = model, reasoningMode = reasoningMode)
    }

    override suspend fun fetchMemoryEnabled(): Boolean = settingDataSource.getMemoryEnabled() ?: false

    override suspend fun fetchWebSearchMode(): WebSearchMode =
        WebSearchMode.fromStorageValue(settingDataSource.getWebSearchMode())

    override suspend fun migrateToPlatformV2() {
        val leftOverPlatformV2s = fetchPlatformV2s()
        leftOverPlatformV2s.forEach { platformV2Dao.deletePlatform(it) }

        val platforms = fetchPlatforms()

        platforms.forEach { platform ->
            val migratedPlatform = PlatformV2(
                name = when (platform.name) {
                    ApiType.OPENAI -> "OpenAI"
                    ApiType.ANTHROPIC -> "Anthropic"
                    ApiType.GOOGLE -> "Google"
                    ApiType.GROQ -> "Groq"
                    ApiType.OLLAMA -> "Ollama"
                },
                compatibleType = when (platform.name) {
                    ApiType.OPENAI -> ClientType.OPENAI
                    ApiType.ANTHROPIC -> ClientType.ANTHROPIC
                    ApiType.GOOGLE -> ClientType.GOOGLE
                    ApiType.GROQ -> ClientType.GROQ
                    ApiType.OLLAMA -> ClientType.OLLAMA
                },
                enabled = platform.enabled,
                apiUrl = if (
                    (platform.name == ApiType.OPENAI || platform.name == ApiType.GROQ) &&
                    platform.apiUrl.endsWith("v1/")
                ) {
                    platform.apiUrl.removeSuffix("v1/")
                } else {
                    platform.apiUrl
                },
                token = platform.token,
                model = platform.model ?: "",
                temperature = platform.temperature,
                topP = platform.topP,
                systemPrompt = platform.systemPrompt,
                stream = true,
                reasoning = false
            )
            platformV2Dao.addPlatform(migratedPlatform)
            persistLegacyModelIfPresent(migratedPlatform)
        }
    }

    override suspend fun updatePlatforms(platforms: List<Platform>) {
        platforms.forEach { platform ->
            settingDataSource.updateStatus(platform.name, platform.enabled)
            settingDataSource.updateAPIUrl(platform.name, platform.apiUrl)

            platform.token?.let { settingDataSource.updateToken(platform.name, it) }
            platform.model?.let { settingDataSource.updateModel(platform.name, it) }
            platform.temperature?.let { settingDataSource.updateTemperature(platform.name, it) }
            platform.topP?.let { settingDataSource.updateTopP(platform.name, it) }
            platform.systemPrompt?.let { settingDataSource.updateSystemPrompt(platform.name, it.trim()) }
        }
    }

    override suspend fun updateThemes(themeSetting: ThemeSetting) {
        settingDataSource.updateDynamicTheme(themeSetting.dynamicTheme)
        settingDataSource.updateThemeMode(themeSetting.themeMode)
    }

    override suspend fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode) {
        val sanitizedPlatformUid = platformUid.trim()
        val sanitizedModel = model.trim()
        if (sanitizedPlatformUid.isBlank() || sanitizedModel.isBlank()) return

        settingDataSource.updateLastSelectedModel(sanitizedPlatformUid, sanitizedModel, reasoningMode.storageValue)
    }

    override suspend fun updateMemoryEnabled(enabled: Boolean) {
        settingDataSource.updateMemoryEnabled(enabled)
    }

    override suspend fun updateWebSearchMode(mode: WebSearchMode) {
        settingDataSource.updateWebSearchMode(mode.storageValue)
    }

    override suspend fun refreshPlatformModels(platformUid: String): ModelRefreshResult {
        val platform = platformV2Dao.getPlatforms().firstOrNull { it.uid == platformUid }
            ?: return ModelRefreshResult(
                platform = PlatformV2(
                    name = "",
                    compatibleType = ClientType.CUSTOM,
                    apiUrl = "",
                    model = ""
                ),
                models = emptyList(),
                errorMessage = "platform_not_found"
            )

        val refreshedAt = System.currentTimeMillis() / 1000
        return runCatching {
            val fetchedModels = modelDiscoveryRepository.fetchModels(
                clientType = platform.compatibleType,
                apiUrl = platform.apiUrl,
                token = platform.token
            )
            val savedModels = mergeFetchedModels(
                platform = platform,
                fetchedModels = fetchedModels,
                refreshedAt = refreshedAt
            )
            val updatedPlatform = platform.copy(
                model = savedModels.firstOrNull { it.isDefault }?.modelId ?: platform.model,
                modelRefreshStatus = PlatformModelRefreshStatus.SUCCESS,
                modelRefreshError = null,
                modelRefreshedAt = refreshedAt
            )
            platformV2Dao.editPlatform(updatedPlatform)
            ModelRefreshResult(platform = updatedPlatform, models = savedModels)
        }.getOrElse { throwable ->
            val errorMessage = throwable.message ?: "model_fetch_failed"
            val updatedPlatform = platform.copy(
                modelRefreshStatus = PlatformModelRefreshStatus.FAILED,
                modelRefreshError = errorMessage,
                modelRefreshedAt = refreshedAt
            )
            platformV2Dao.editPlatform(updatedPlatform)
            ModelRefreshResult(
                platform = updatedPlatform,
                models = platformModelV2Dao.getModelsForPlatform(platform.uid),
                errorMessage = errorMessage
            )
        }
    }

    override suspend fun updatePlatformModelEnabled(platformUid: String, modelId: String, enabled: Boolean) {
        val sanitizedModelId = modelId.trim()
        if (platformUid.isBlank() || sanitizedModelId.isBlank()) return

        val updatedAt = System.currentTimeMillis() / 1000
        platformModelV2Dao.updateEnabled(platformUid, sanitizedModelId, enabled, updatedAt)
        val models = platformModelV2Dao.getModelsForPlatform(platformUid)
        val disabledDefault = models.any { it.modelId == sanitizedModelId && it.isDefault && !it.enabled }
        if (disabledDefault) {
            val nextDefault = models.firstOrNull { it.enabled }
            if (nextDefault == null) {
                platformModelV2Dao.clearDefault(platformUid, sanitizedModelId, updatedAt)
                platformV2Dao.getPlatforms().firstOrNull { it.uid == platformUid }?.let { platform ->
                    platformV2Dao.editPlatform(platform.copy(model = ""))
                }
            } else {
                setPlatformDefaultModel(platformUid, nextDefault.modelId)
            }
        }
    }

    override suspend fun setPlatformDefaultModel(platformUid: String, modelId: String) {
        val sanitizedModelId = modelId.trim()
        if (platformUid.isBlank() || sanitizedModelId.isBlank()) return

        val updatedAt = System.currentTimeMillis() / 1000
        platformModelV2Dao.setDefault(platformUid, sanitizedModelId, updatedAt)
        platformV2Dao.getPlatforms().firstOrNull { it.uid == platformUid }?.let { platform ->
            platformV2Dao.editPlatform(platform.copy(model = sanitizedModelId))
        }
    }

    override suspend fun addPlatformV2(platform: PlatformV2) {
        platformV2Dao.addPlatform(platform)
        persistLegacyModelIfPresent(platform)
    }

    override suspend fun updatePlatformV2(platform: PlatformV2) {
        val existingPlatform = platformV2Dao.getPlatforms().firstOrNull { it.uid == platform.uid }
        val shouldMarkModelsStale = existingPlatform != null &&
            (existingPlatform.apiUrl != platform.apiUrl || existingPlatform.token != platform.token)
        val updatedPlatform = if (shouldMarkModelsStale) {
            platform.copy(
                modelRefreshStatus = PlatformModelRefreshStatus.NOT_LOADED,
                modelRefreshError = null,
                modelRefreshedAt = null
            )
        } else {
            platform
        }

        platformV2Dao.editPlatform(updatedPlatform)
        persistLegacyModelIfPresent(updatedPlatform)
    }

    override suspend fun deletePlatformV2(platform: PlatformV2) {
        platformModelV2Dao.deleteByPlatformUid(platform.uid)
        chatPlatformModelV2Dao.deleteByPlatformUid(platform.uid)
        platformV2Dao.deletePlatform(platform)
    }

    private suspend fun mergeFetchedModels(
        platform: PlatformV2,
        fetchedModels: List<APIModel>,
        refreshedAt: Long
    ): List<PlatformModelV2> {
        val existingModels = platformModelV2Dao.getModelsForPlatform(platform.uid)
        val existingById = existingModels.associateBy { it.modelId }
        val fetchedRows = fetchedModels
            .mapNotNull { apiModel ->
                val modelId = apiModel.aliasValue.trim()
                if (modelId.isBlank()) return@mapNotNull null

                val existingModel = existingById[modelId]
                PlatformModelV2(
                    platformUid = platform.uid,
                    modelId = modelId,
                    displayName = apiModel.name.ifBlank { modelId },
                    description = apiModel.description,
                    enabled = existingModel?.enabled ?: true,
                    isDefault = existingModel?.isDefault ?: false,
                    updatedAt = refreshedAt
                )
            }
            .distinctBy { it.modelId }

        if (fetchedRows.isEmpty()) {
            return existingModels
        }

        val fetchedIds = fetchedRows.map { it.modelId }.toSet()
        val defaultModelId = when {
            fetchedRows.any { it.isDefault && it.enabled } -> fetchedRows.first { it.isDefault && it.enabled }.modelId
            platform.model.isNotBlank() && platform.model in fetchedIds -> platform.model
            else -> fetchedRows.firstOrNull { it.enabled }?.modelId ?: fetchedRows.first().modelId
        }

        platformModelV2Dao.upsertModels(
            fetchedRows.map { row ->
                row.copy(enabled = row.enabled || row.modelId == defaultModelId)
            }
        )
        platformModelV2Dao.setDefault(platform.uid, defaultModelId, refreshedAt)

        return platformModelV2Dao.getModelsForPlatform(platform.uid)
    }

    private suspend fun persistLegacyModelIfPresent(platform: PlatformV2) {
        val modelId = platform.model.trim()
        if (platform.uid.isBlank() || modelId.isBlank()) return

        val existingModel = platformModelV2Dao.getModel(platform.uid, modelId)
        if (existingModel == null) {
            platformModelV2Dao.upsertModels(
                listOf(
                    PlatformModelV2(
                        platformUid = platform.uid,
                        modelId = modelId,
                        displayName = modelId,
                        enabled = true,
                        isDefault = true
                    )
                )
            )
        }
        platformModelV2Dao.setDefault(platform.uid, modelId, System.currentTimeMillis() / 1000)
    }

    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = platformV2Dao.getPlatform(id)
}
