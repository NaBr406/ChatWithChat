package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.tool.ResolvedToolCatalogEntry
import dev.chungjungsoo.gptmobile.data.tool.ToolCallingMode
import dev.chungjungsoo.gptmobile.data.tool.ToolEnablementOverrides
import dev.chungjungsoo.gptmobile.data.tool.ToolEnablementResolver
import dev.chungjungsoo.gptmobile.data.tool.ToolRegistry
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchMode
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class SettingViewModelV2 @Inject constructor(
    private val settingRepository: SettingRepository,
    private val memoryRepository: MemoryRepository,
    private val toolRegistry: ToolRegistry,
    private val toolEnablementResolver: ToolEnablementResolver
) : ViewModel() {

    private val _platformState = MutableStateFlow(listOf<PlatformV2>())
    val platformState: StateFlow<List<PlatformV2>> = _platformState.asStateFlow()

    private val _memoryEnabled = MutableStateFlow(false)
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    private val _memoryMaintenanceNotificationsEnabled = MutableStateFlow(true)
    val memoryMaintenanceNotificationsEnabled: StateFlow<Boolean> = _memoryMaintenanceNotificationsEnabled.asStateFlow()

    private val _webSearchSettings = MutableStateFlow(WebSearchSettingsState())
    val webSearchSettings: StateFlow<WebSearchSettingsState> = _webSearchSettings.asStateFlow()

    private val _modelManagementState = MutableStateFlow(ModelManagementState())
    val modelManagementState: StateFlow<ModelManagementState> = _modelManagementState.asStateFlow()

    private val _addPlatformSaveState = MutableStateFlow<AddPlatformSaveState>(AddPlatformSaveState.Idle)
    val addPlatformSaveState: StateFlow<AddPlatformSaveState> = _addPlatformSaveState.asStateFlow()

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private var lastAddedPlatformUid: String? = null
    private var webSearchBaseUrlSaveJob: Job? = null
    private val toolSettingsMutex = Mutex()

    init {
        fetchPlatforms()
        fetchMemoryEnabled()
        fetchMemoryMaintenanceNotificationsEnabled()
        fetchWebSearchSettings()
    }

    fun fetchPlatforms() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            _platformState.update { platforms }
            fetchPlatformModels(platforms)
        }
    }

    private suspend fun fetchPlatformModels(platforms: List<PlatformV2>) {
        val modelsByPlatformUid = platforms.associate { platform ->
            platform.uid to settingRepository.fetchPlatformModels(platform.uid)
        }
        _modelManagementState.update { currentState ->
            currentState.copy(
                platforms = platforms,
                modelsByPlatformUid = modelsByPlatformUid
            )
        }
    }

    fun fetchMemoryEnabled() {
        viewModelScope.launch {
            _memoryEnabled.update { settingRepository.fetchMemoryEnabled() }
        }
    }

    fun updateMemoryEnabled(enabled: Boolean) {
        _memoryEnabled.update { enabled }
        viewModelScope.launch {
            settingRepository.updateMemoryEnabled(enabled)
            memoryRepository.onMemoryEnabledChanged(enabled)
        }
    }

    fun fetchMemoryMaintenanceNotificationsEnabled() {
        viewModelScope.launch {
            _memoryMaintenanceNotificationsEnabled.update {
                settingRepository.fetchMemoryMaintenanceNotificationsEnabled()
            }
        }
    }

    fun updateMemoryMaintenanceNotificationsEnabled(enabled: Boolean) {
        _memoryMaintenanceNotificationsEnabled.update { enabled }
        viewModelScope.launch {
            settingRepository.updateMemoryMaintenanceNotificationsEnabled(enabled)
        }
    }

    fun fetchWebSearchSettings() {
        viewModelScope.launch {
            toolSettingsMutex.withLock {
                val toolEnablementOverrides = settingRepository.fetchToolEnablementOverrides()
                _webSearchSettings.update {
                    WebSearchSettingsState(
                        toolCallingMode = settingRepository.fetchToolCallingMode(),
                        toolEnablementOverrides = toolEnablementOverrides,
                        tools = visibleTools(toolEnablementOverrides),
                        mode = settingRepository.fetchWebSearchMode(),
                        searxngBaseUrl = settingRepository.fetchWebSearchSearxngBaseUrl()
                    )
                }
            }
        }
    }

    fun updateToolCallingMode(mode: ToolCallingMode) {
        _webSearchSettings.update { currentState -> currentState.copy(toolCallingMode = mode) }
        viewModelScope.launch {
            settingRepository.updateToolCallingMode(mode)
        }
    }

    fun updateToolEnabled(toolName: String, enabled: Boolean) {
        if (toolRegistry.catalogEntryFor(toolName)?.settings?.userVisible != true) return
        viewModelScope.launch {
            toolSettingsMutex.withLock {
                settingRepository.updateToolEnabled(toolName, enabled)
                val overrides = settingRepository.fetchToolEnablementOverrides()
                _webSearchSettings.update { currentState ->
                    currentState.copy(
                        toolEnablementOverrides = overrides,
                        tools = visibleTools(overrides)
                    )
                }
            }
        }
    }

    private fun visibleTools(overrides: ToolEnablementOverrides): List<ResolvedToolCatalogEntry> =
        toolEnablementResolver.resolve(toolRegistry.catalog, overrides)
            .filter { entry -> entry.catalogEntry.settings.userVisible }

    fun updateWebSearchMode(mode: WebSearchMode) {
        _webSearchSettings.update { currentState -> currentState.copy(mode = mode) }
        viewModelScope.launch {
            settingRepository.updateWebSearchMode(mode)
        }
    }

    fun updateWebSearchSearxngBaseUrl(baseUrl: String) {
        val hasError = !isValidWebSearchBaseUrl(baseUrl)
        _webSearchSettings.update { currentState ->
            currentState.copy(
                searxngBaseUrl = baseUrl,
                searxngBaseUrlError = hasError
            )
        }

        webSearchBaseUrlSaveJob?.cancel()
        if (hasError) return

        webSearchBaseUrlSaveJob = viewModelScope.launch {
            settingRepository.updateWebSearchSearxngBaseUrl(baseUrl)
        }
    }

    fun refreshPlatformModels(platformUid: String) {
        viewModelScope.launch {
            _modelManagementState.update { state -> state.copy(refreshingPlatformUids = state.refreshingPlatformUids + platformUid) }
            settingRepository.refreshPlatformModels(platformUid)
            val platforms = settingRepository.fetchPlatformV2s()
            _platformState.update { platforms }
            fetchPlatformModels(platforms)
            _modelManagementState.update { state -> state.copy(refreshingPlatformUids = state.refreshingPlatformUids - platformUid) }
        }
    }

    fun updatePlatformModelEnabled(platformUid: String, modelId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingRepository.updatePlatformModelEnabled(platformUid, modelId, enabled)
            val platforms = settingRepository.fetchPlatformV2s()
            _platformState.update { platforms }
            fetchPlatformModels(platforms)
        }
    }

    fun setPlatformDefaultModel(platformUid: String, modelId: String) {
        viewModelScope.launch {
            settingRepository.setPlatformDefaultModel(platformUid, modelId)
            val platforms = settingRepository.fetchPlatformV2s()
            _platformState.update { platforms }
            fetchPlatformModels(platforms)
        }
    }

    fun addPlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.addPlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun addPlatformAndRefreshModels(platform: PlatformV2) {
        viewModelScope.launch {
            _addPlatformSaveState.update { AddPlatformSaveState.Saving }
            runCatching {
                settingRepository.addPlatformV2(platform)
                lastAddedPlatformUid = platform.uid
                _addPlatformSaveState.update { AddPlatformSaveState.RefreshingModels }
                refreshSavedPlatformModels(platform.uid)
            }.onFailure { throwable ->
                _addPlatformSaveState.update {
                    AddPlatformSaveState.Error(
                        message = throwable.message ?: "save_platform_failed",
                        platformSaved = false
                    )
                }
            }
        }
    }

    fun retryAddedPlatformModelRefresh() {
        val platformUid = lastAddedPlatformUid ?: return
        viewModelScope.launch {
            _addPlatformSaveState.update { AddPlatformSaveState.RefreshingModels }
            refreshSavedPlatformModels(platformUid)
        }
    }

    fun clearAddPlatformSaveState() {
        _addPlatformSaveState.update { AddPlatformSaveState.Idle }
        lastAddedPlatformUid = null
    }

    fun updatePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.updatePlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun deletePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.deletePlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun togglePlatformEnabled(platformId: Int) {
        val platform = _platformState.value.find { it.id == platformId }
        platform?.let {
            updatePlatform(it.copy(enabled = !it.enabled))
        }
    }

    fun openDeleteDialog(platformId: Int) = _dialogState.update {
        it.copy(
            isDeleteDialogOpen = true,
            platformToDelete = platformId
        )
    }

    fun closeDeleteDialog() = _dialogState.update {
        it.copy(
            isDeleteDialogOpen = false,
            platformToDelete = null
        )
    }

    fun confirmDelete() {
        _dialogState.value.platformToDelete?.let { platformId ->
            val platform = _platformState.value.find { it.id == platformId }
            platform?.let { deletePlatform(it) }
        }
        closeDeleteDialog()
    }

    data class DialogState(
        val isDeleteDialogOpen: Boolean = false,
        val platformToDelete: Int? = null
    )

    data class WebSearchSettingsState(
        val toolCallingMode: ToolCallingMode = ToolCallingMode.Auto,
        val toolEnablementOverrides: ToolEnablementOverrides = ToolEnablementOverrides(),
        val tools: List<ResolvedToolCatalogEntry> = emptyList(),
        val mode: WebSearchMode = WebSearchMode.Off,
        val searxngBaseUrl: String = "",
        val searxngBaseUrlError: Boolean = false
    )

    sealed class AddPlatformSaveState {
        data object Idle : AddPlatformSaveState()
        data object Saving : AddPlatformSaveState()
        data object RefreshingModels : AddPlatformSaveState()
        data class Success(val modelCount: Int) : AddPlatformSaveState()
        data class Error(val message: String, val platformSaved: Boolean) : AddPlatformSaveState()
    }

    data class ModelManagementState(
        val platforms: List<PlatformV2> = emptyList(),
        val modelsByPlatformUid: Map<String, List<PlatformModelV2>> = emptyMap(),
        val refreshingPlatformUids: Set<String> = emptySet()
    )

    private suspend fun refreshSavedPlatformModels(platformUid: String) {
        val result = settingRepository.refreshPlatformModels(platformUid)
        val platforms = settingRepository.fetchPlatformV2s()
        _platformState.update { platforms }
        fetchPlatformModels(platforms)
        _addPlatformSaveState.update {
            if (result.isSuccess) {
                AddPlatformSaveState.Success(modelCount = result.models.count { model -> model.enabled })
            } else {
                AddPlatformSaveState.Error(
                    message = result.errorMessage ?: "model_fetch_failed",
                    platformSaved = true
                )
            }
        }
    }

    private fun isValidWebSearchBaseUrl(baseUrl: String): Boolean {
        val trimmedBaseUrl = baseUrl.trim()
        if (trimmedBaseUrl.isBlank()) return true

        val uri = runCatching { URI(trimmedBaseUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    }
}
