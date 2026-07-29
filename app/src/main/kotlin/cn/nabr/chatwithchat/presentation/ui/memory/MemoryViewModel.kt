package cn.nabr.chatwithchat.presentation.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.memory.MemoryModelPreference
import cn.nabr.chatwithchat.data.memory.MemoryModelResolver
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.repository.MemoryRepository
import cn.nabr.chatwithchat.data.repository.SettingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val settingRepository: SettingRepository,
    private val memoryModelResolver: MemoryModelResolver,
    memoryActivityLogDao: MemoryActivityLogDao
) : ViewModel() {

    data class UiState(
        val markdown: String = "",
        val exportMarkdown: String? = null,
        val memoryEnabled: Boolean = false,
        val memoryModelPreference: MemoryModelPreference = MemoryModelPreference.Auto,
        val memoryModelOptions: List<MemoryModelOption> = emptyList(),
        val isMemoryModelLoading: Boolean = false,
        val isMemoryModelSaving: Boolean = false,
        val isMemoryModelPickerOpen: Boolean = false,
        val memoryModelError: MemoryModelUiError? = null,
        val activityLogs: List<MemoryActivityLog> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            memoryRepository.observeLongTermMarkdown().collect { markdown ->
                _uiState.update { it.copy(markdown = markdown) }
            }
        }
        viewModelScope.launch {
            val memoryEnabled = settingRepository.fetchMemoryEnabled()
            _uiState.update { it.copy(memoryEnabled = memoryEnabled) }
        }
        refreshMemoryModels()
        viewModelScope.launch {
            memoryActivityLogDao.observeLatest().collect { logs ->
                _uiState.update { it.copy(activityLogs = logs) }
            }
        }
    }

    fun exportMarkdown() {
        viewModelScope.launch {
            val markdown = memoryRepository.getLongTermMarkdown()
            _uiState.update { it.copy(markdown = markdown, exportMarkdown = markdown) }
        }
    }

    fun closeExport() {
        _uiState.update { it.copy(exportMarkdown = null) }
    }

    fun refreshMemoryModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isMemoryModelLoading = true, memoryModelError = null) }
            try {
                val preference = settingRepository.fetchMemoryModelPreference()
                val options = settingRepository.fetchEnabledChatModels()
                    .filter(memoryModelResolver::isEligible)
                    .map(AvailableChatModel::toMemoryModelOption)
                _uiState.update {
                    it.copy(
                        memoryModelPreference = preference,
                        memoryModelOptions = options,
                        isMemoryModelLoading = false
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                _uiState.update {
                    it.copy(
                        isMemoryModelLoading = false,
                        memoryModelError = MemoryModelUiError.LOAD_FAILED
                    )
                }
            }
        }
    }

    fun openMemoryModelPicker() {
        _uiState.update { it.copy(isMemoryModelPickerOpen = true) }
    }

    fun closeMemoryModelPicker() {
        if (_uiState.value.isMemoryModelSaving) return
        _uiState.update { it.copy(isMemoryModelPickerOpen = false) }
    }

    fun selectMemoryModel(preference: MemoryModelPreference) {
        if (preference is MemoryModelPreference.Invalid) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMemoryModelSaving = true, memoryModelError = null) }
            try {
                settingRepository.updateMemoryModelPreference(preference)
                _uiState.update {
                    it.copy(
                        memoryModelPreference = preference,
                        isMemoryModelSaving = false,
                        isMemoryModelPickerOpen = false
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                _uiState.update {
                    it.copy(
                        isMemoryModelSaving = false,
                        memoryModelError = MemoryModelUiError.SAVE_FAILED
                    )
                }
            }
        }
    }
}

data class MemoryModelOption(
    val platformUid: String,
    val modelId: String,
    val platformName: String,
    val modelName: String
) {
    val preference: MemoryModelPreference.Fixed
        get() = MemoryModelPreference.Fixed(platformUid, modelId)
}

enum class MemoryModelUiError {
    LOAD_FAILED,
    SAVE_FAILED
}

private fun AvailableChatModel.toMemoryModelOption(): MemoryModelOption = MemoryModelOption(
    platformUid = platformUid,
    modelId = modelId,
    platformName = platformName,
    modelName = displayName
)
