package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cn.nabr.chatwithchat.data.context.ContextStrategy
import cn.nabr.chatwithchat.data.context.ContextWindowResolution
import cn.nabr.chatwithchat.data.context.ContextWindowResolver
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.datastore.ContextWindowOverrideStore
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PlatformSettingViewModel @Inject constructor(
    private val settingRepository: SettingRepository,
    private val contextWindowResolver: ContextWindowResolver,
    private val contextWindowOverrideStore: ContextWindowOverrideStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val platformUid: String = checkNotNull(savedStateHandle["platformUid"])

    private val _platformState = MutableStateFlow<PlatformV2?>(null)
    val platformState: StateFlow<PlatformV2?> = _platformState.asStateFlow()

    /**
     * The effective model window and whether it came from a real discovery/curated table or merely a
     * conservative provider fallback. The strategy page uses this to warn before a user chooses a
     * budget tier against an unknown model.
     */
    private val _contextWindowResolution = MutableStateFlow<ContextWindowResolution?>(null)
    val contextWindowResolution: StateFlow<ContextWindowResolution?> =
        _contextWindowResolution.asStateFlow()

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    /**
     * Set when persisting the context strategy fails. The strategy page applies selections
     * immediately with no confirm step, so a silent failure would leave the checkmark showing a
     * value that was never written.
     */
    private val _contextStrategySaveError = MutableStateFlow(false)
    val contextStrategySaveError: StateFlow<Boolean> = _contextStrategySaveError.asStateFlow()

    init {
        loadPlatform()
    }

    private fun loadPlatform() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            val platform = platforms.firstOrNull { it.uid == platformUid }
            _platformState.update { platform }
            platform?.let { refreshContextWindowResolution(it) }
        }
    }

    fun toggleEnabled() {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(enabled = !platform.enabled))
        }
    }

    fun updatePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.updatePlatformV2(platform)
            _platformState.update { platform }
            refreshContextWindowResolution(platform)
        }
    }

    private suspend fun refreshContextWindowResolution(platform: PlatformV2) {
        _contextWindowResolution.value = runCatching {
            contextWindowResolver.resolveDetailed(platform)
        }.getOrNull()
    }

    fun openPlatformNameDialog() = _dialogState.update { it.copy(isPlatformNameDialogOpen = true) }
    fun closePlatformNameDialog() = _dialogState.update { it.copy(isPlatformNameDialogOpen = false) }

    fun openApiUrlDialog() = _dialogState.update { it.copy(isApiUrlDialogOpen = true) }
    fun closeApiUrlDialog() = _dialogState.update { it.copy(isApiUrlDialogOpen = false) }

    fun openApiTokenDialog() = _dialogState.update { it.copy(isApiTokenDialogOpen = true) }
    fun closeApiTokenDialog() = _dialogState.update { it.copy(isApiTokenDialogOpen = false) }

    fun openTemperatureDialog() = _dialogState.update { it.copy(isTemperatureDialogOpen = true) }
    fun closeTemperatureDialog() = _dialogState.update { it.copy(isTemperatureDialogOpen = false) }

    fun openTopPDialog() = _dialogState.update { it.copy(isTopPDialogOpen = true) }
    fun closeTopPDialog() = _dialogState.update { it.copy(isTopPDialogOpen = false) }

    fun openSystemPromptDialog() = _dialogState.update { it.copy(isSystemPromptDialogOpen = true) }
    fun closeSystemPromptDialog() = _dialogState.update { it.copy(isSystemPromptDialogOpen = false) }

    fun openTimeoutDialog() = _dialogState.update { it.copy(isTimeoutDialogOpen = true) }
    fun closeTimeoutDialog() = _dialogState.update { it.copy(isTimeoutDialogOpen = false) }

    fun consumeContextStrategySaveError() = _contextStrategySaveError.update { false }

    /**
     * Persists the provider default only. The per-chat range lives in the checkpoint store keyed by
     * `chatRoomId + platformUid` and is deliberately untouched here: changing the default must not
     * silently rewrite ranges the user picked for individual conversations.
     *
     * Applied immediately from the strategy page rather than behind a confirm button, so the local
     * state is only advanced once the write succeeds.
     */
    fun updateContextStrategy(strategy: ContextStrategy) {
        _platformState.value?.let { platform ->
            val updated = platform.copy(contextStrategy = strategy.name)
            viewModelScope.launch {
                runCatching { settingRepository.updatePlatformV2(updated) }
                    .onSuccess { _platformState.update { updated } }
                    .onFailure { _contextStrategySaveError.update { true } }
            }
        }
    }

    fun updateContextWindowOverride(tokens: Int?) {
        val platform = _platformState.value ?: return
        viewModelScope.launch {
            contextWindowOverrideStore.setContextWindowOverride(platform.uid, tokens)
            refreshContextWindowResolution(platform)
        }
    }

    fun updatePlatformName(name: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(name = name.trim()))
            closePlatformNameDialog()
        }
    }

    fun updateApiUrl(url: String, isFullApiUrl: Boolean) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(apiUrl = url.trim(), isFullApiUrl = isFullApiUrl))
            closeApiUrlDialog()
        }
    }

    fun updateApiToken(token: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(token = token.trim().takeIf { it.isNotEmpty() }))
            closeApiTokenDialog()
        }
    }

    fun updateCustomHeaders(headers: String) {
        _platformState.value?.let { platform ->
            updatePlatform(platform.copy(customHeaders = headers.trim()))
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
        val isTemperatureDialogOpen: Boolean = false,
        val isTopPDialogOpen: Boolean = false,
        val isSystemPromptDialogOpen: Boolean = false,
        val isTimeoutDialogOpen: Boolean = false,
        val isDeleteDialogOpen: Boolean = false
    )

}
