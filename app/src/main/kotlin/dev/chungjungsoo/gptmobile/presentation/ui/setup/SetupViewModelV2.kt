package dev.chungjungsoo.gptmobile.presentation.ui.setup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class SaveStatus {
    data object Idle : SaveStatus()
    data object Saving : SaveStatus()
    data object RefreshingModels : SaveStatus()
    data class Success(val modelCount: Int) : SaveStatus()
    data class Error(val message: String, val platformSaved: Boolean) : SaveStatus()
}

@HiltViewModel
class SetupViewModelV2 @Inject constructor(
    private val settingRepository: SettingRepository
) : ViewModel() {

    private val _platforms = MutableStateFlow<List<PlatformV2>>(emptyList())
    val platforms: StateFlow<List<PlatformV2>> = _platforms.asStateFlow()

    // Wizard state for adding a new platform
    private val _wizardStep = MutableStateFlow(0)
    val wizardStep: StateFlow<Int> = _wizardStep.asStateFlow()

    private val _selectedClientType = MutableStateFlow<ClientType?>(null)
    val selectedClientType: StateFlow<ClientType?> = _selectedClientType.asStateFlow()

    private val _platformName = MutableStateFlow("")
    val platformName: StateFlow<String> = _platformName.asStateFlow()

    private val _apiUrl = MutableStateFlow("")
    val apiUrl: StateFlow<String> = _apiUrl.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _saveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    private var lastSavedPlatformUid: String? = null

    init {
        loadPlatforms()
    }

    private fun loadPlatforms() {
        viewModelScope.launch {
            reloadPlatforms()
        }
    }

    private suspend fun reloadPlatforms() {
        val existingPlatforms = settingRepository.fetchPlatformV2s()
        _platforms.value = existingPlatforms
    }

    fun selectClientType(clientType: ClientType) {
        _selectedClientType.value = clientType
        _platformName.value = getDefaultPlatformName(clientType)
        _apiUrl.value = getDefaultApiUrl(clientType)
        _apiKey.value = ""
        _wizardStep.value = 0
    }

    fun updatePlatformName(name: String) {
        _platformName.value = name
    }

    fun updateApiUrl(url: String) {
        _apiUrl.value = url
    }

    fun updateApiKey(key: String) {
        _apiKey.value = key
    }

    fun nextWizardStep() {
        _wizardStep.update { it + 1 }
    }

    fun previousWizardStep() {
        _wizardStep.update { maxOf(0, it - 1) }
    }

    fun resetWizard() {
        _wizardStep.value = 0
        _selectedClientType.value = null
        _platformName.value = ""
        _apiUrl.value = ""
        _apiKey.value = ""
    }

    fun savePlatform() {
        val clientType = _selectedClientType.value ?: return
        if (_saveStatus.value is SaveStatus.Saving || _saveStatus.value is SaveStatus.RefreshingModels) return

        viewModelScope.launch {
            _saveStatus.value = SaveStatus.Saving
            try {
                val platform = PlatformV2(
                    name = _platformName.value.trim(),
                    compatibleType = clientType,
                    enabled = true,
                    apiUrl = _apiUrl.value.trim(),
                    token = _apiKey.value.trim().takeIf { it.isNotEmpty() },
                    model = "",
                    temperature = 1.0f,
                    topP = 1.0f,
                    systemPrompt = null,
                    stream = true,
                    reasoning = false,
                    timeout = 30
                )
                settingRepository.addPlatformV2(platform)
                lastSavedPlatformUid = platform.uid
                _saveStatus.value = SaveStatus.RefreshingModels
                refreshSavedPlatformModels(platform.uid)
                resetWizard()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save platform", e)
                val errorMessage = when (e) {
                    is android.database.sqlite.SQLiteConstraintException -> "已存在同名平台。"
                    is android.database.sqlite.SQLiteException -> "数据库错误：${e.message}"
                    else -> e.message ?: "保存平台时发生未知错误。"
                }
                _saveStatus.value = SaveStatus.Error(errorMessage, platformSaved = false)
            }
        }
    }

    fun retrySavedPlatformModelRefresh() {
        val platformUid = lastSavedPlatformUid ?: return
        viewModelScope.launch {
            _saveStatus.value = SaveStatus.RefreshingModels
            refreshSavedPlatformModels(platformUid)
        }
    }

    fun clearSaveStatus() {
        _saveStatus.value = SaveStatus.Idle
        lastSavedPlatformUid = null
    }

    fun deletePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.deletePlatformV2(platform)
            loadPlatforms()
        }
    }

    fun canProceedFromStep(step: Int): Boolean = when (step) {
        0 -> _platformName.value.isNotBlank() && _apiUrl.value.isNotBlank()

        1 -> true

        // API key is optional for some providers (e.g., Ollama)
        2 -> true

        else -> false
    }

    fun isSetupComplete(): Boolean = _platforms.value.isNotEmpty()

    private fun getDefaultPlatformName(clientType: ClientType): String = when (clientType) {
        ClientType.OPENAI -> "OpenAI"
        ClientType.ANTHROPIC -> "Anthropic"
        ClientType.GOOGLE -> "Google"
        ClientType.GROQ -> "Groq"
        ClientType.OLLAMA -> "Ollama"
        ClientType.OPENROUTER -> "OpenRouter"
        ClientType.CUSTOM -> ""
    }

    private fun getDefaultApiUrl(clientType: ClientType): String = when (clientType) {
        ClientType.OPENAI -> ModelConstants.OPENAI_API_URL
        ClientType.ANTHROPIC -> ModelConstants.ANTHROPIC_API_URL
        ClientType.GOOGLE -> ModelConstants.GOOGLE_API_URL
        ClientType.GROQ -> ModelConstants.GROQ_API_URL
        ClientType.OLLAMA -> "http://localhost:11434/"
        ClientType.OPENROUTER -> ModelConstants.OPENROUTER_API_URL
        ClientType.CUSTOM -> ""
    }

    private suspend fun refreshSavedPlatformModels(platformUid: String) {
        val result = settingRepository.refreshPlatformModels(platformUid)
        reloadPlatforms()
        _saveStatus.value = if (result.isSuccess) {
            SaveStatus.Success(modelCount = result.models.count { model -> model.enabled })
        } else {
            SaveStatus.Error(
                message = result.errorMessage ?: "model_fetch_failed",
                platformSaved = true
            )
        }
    }

    companion object {
        private const val TAG = "SetupViewModelV2"
        const val WIZARD_STEP_BASICS = 0
        const val WIZARD_STEP_API_KEY = 1
        const val WIZARD_TOTAL_STEPS = 2
    }
}
