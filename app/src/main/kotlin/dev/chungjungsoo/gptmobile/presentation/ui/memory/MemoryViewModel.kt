package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryActivityLogDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryActivityLog
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val settingRepository: SettingRepository,
    memoryActivityLogDao: MemoryActivityLogDao
) : ViewModel() {

    data class UiState(
        val markdown: String = "",
        val exportMarkdown: String? = null,
        val memoryEnabled: Boolean = false,
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
}
