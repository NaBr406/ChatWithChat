package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.APIModel
import dev.chungjungsoo.gptmobile.data.repository.ModelDiscoveryRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PlatformSettingViewModel @Inject constructor(
    private val settingRepository: SettingRepository,
    private val modelDiscoveryRepository: ModelDiscoveryRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val platformUid: String = checkNotNull(savedStateHandle["platformUid"])

    private val _platformState = MutableStateFlow<PlatformV2?>(null)
    val platformState: StateFlow<PlatformV2?> = _platformState.asStateFlow()

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _modelFetchState = MutableStateFlow(ModelFetchState())
    val modelFetchState: StateFlow<ModelFetchState> = _modelFetchState.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    init {
        loadPlatform()
    }

    private fun loadPlatform() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            val platform = platforms.firstOrNull { it.uid == platformUid }
            _platformState.update { platform }
        }
    }

    fun toggleEnabled() {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(enabled = !platform.enabled))
        }
    }

    fun toggleReasoning() {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(reasoning = !platform.reasoning))
        }
    }

    fun updatePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.updatePlatformV2(platform)
            _platformState.update { platform }
        }
    }

    fun openPlatformNameDialog() = _dialogState.update { it.copy(isPlatformNameDialogOpen = true) }
    fun closePlatformNameDialog() = _dialogState.update { it.copy(isPlatformNameDialogOpen = false) }

    fun openApiUrlDialog() = _dialogState.update { it.copy(isApiUrlDialogOpen = true) }
    fun closeApiUrlDialog() = _dialogState.update { it.copy(isApiUrlDialogOpen = false) }

    fun openApiTokenDialog() = _dialogState.update { it.copy(isApiTokenDialogOpen = true) }
    fun closeApiTokenDialog() = _dialogState.update { it.copy(isApiTokenDialogOpen = false) }

    fun openApiModelDialog() {
        _dialogState.update { it.copy(isApiModelDialogOpen = true) }
        fetchAvailableModels()
    }
    fun closeApiModelDialog() = _dialogState.update { it.copy(isApiModelDialogOpen = false) }

    fun fetchAvailableModels() {
        val platform = _platformState.value ?: return
        viewModelScope.launch {
            _modelFetchState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                modelDiscoveryRepository.fetchModels(platform.compatibleType, platform.apiUrl, platform.token)
            }.onSuccess { models ->
                _modelFetchState.update { ModelFetchState(models = models, hasLoaded = true) }
            }.onFailure { throwable ->
                _modelFetchState.update {
                    it.copy(
                        isLoading = false,
                        hasLoaded = true,
                        errorMessage = throwable.message ?: "model_fetch_failed"
                    )
                }
            }
        }
    }

    fun openTemperatureDialog() = _dialogState.update { it.copy(isTemperatureDialogOpen = true) }
    fun closeTemperatureDialog() = _dialogState.update { it.copy(isTemperatureDialogOpen = false) }

    fun openTopPDialog() = _dialogState.update { it.copy(isTopPDialogOpen = true) }
    fun closeTopPDialog() = _dialogState.update { it.copy(isTopPDialogOpen = false) }

    fun openSystemPromptDialog() = _dialogState.update { it.copy(isSystemPromptDialogOpen = true) }
    fun closeSystemPromptDialog() = _dialogState.update { it.copy(isSystemPromptDialogOpen = false) }

    fun openTimeoutDialog() = _dialogState.update { it.copy(isTimeoutDialogOpen = true) }
    fun closeTimeoutDialog() = _dialogState.update { it.copy(isTimeoutDialogOpen = false) }

    fun updatePlatformName(name: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(name = name.trim()))
            closePlatformNameDialog()
        }
    }

    fun updateApiUrl(url: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(apiUrl = url.trim()))
            closeApiUrlDialog()
        }
    }

    fun updateApiToken(token: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(token = token.trim().takeIf { it.isNotEmpty() }))
            closeApiTokenDialog()
        }
    }

    fun updateApiModel(model: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(model = model.trim()))
            closeApiModelDialog()
        }
    }

    fun updateTemperature(temperature: Float?) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(temperature = temperature))
            closeTemperatureDialog()
        }
    }

    fun updateTopP(topP: Float?) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(topP = topP))
            closeTopPDialog()
        }
    }

    fun updateSystemPrompt(prompt: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(systemPrompt = prompt.trim()))
            closeSystemPromptDialog()
        }
    }

    fun updateTimeout(timeoutSeconds: Int) {
        _platformState.value?.let { platform ->
            val normalizedTimeout = timeoutSeconds.coerceAtLeast(0)
            updatePlatform(platform.copy(timeout = normalizedTimeout))
            closeTimeoutDialog()
        }
    }

    fun openDeleteDialog() = _dialogState.update { it.copy(isDeleteDialogOpen = true) }
    fun closeDeleteDialog() = _dialogState.update { it.copy(isDeleteDialogOpen = false) }

    fun deletePlatform() {
        _platformState.value?.let { platform ->
            viewModelScope.launch {
                settingRepository.deletePlatformV2(platform)
                closeDeleteDialog()
                _isDeleted.update { true }
            }
        }
    }

    data class DialogState(
        val isPlatformNameDialogOpen: Boolean = false,
        val isApiUrlDialogOpen: Boolean = false,
        val isApiTokenDialogOpen: Boolean = false,
        val isApiModelDialogOpen: Boolean = false,
        val isTemperatureDialogOpen: Boolean = false,
        val isTopPDialogOpen: Boolean = false,
        val isSystemPromptDialogOpen: Boolean = false,
        val isTimeoutDialogOpen: Boolean = false,
        val isDeleteDialogOpen: Boolean = false
    )

    data class ModelFetchState(
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val models: List<APIModel> = emptyList(),
        val errorMessage: String? = null
    )
}
