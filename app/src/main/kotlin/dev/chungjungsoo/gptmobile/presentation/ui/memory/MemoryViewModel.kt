package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
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
    private val settingRepository: SettingRepository
) : ViewModel() {

    data class UiState(
        val markdown: String = "",
        val exportMarkdown: String? = null,
        val memoryEnabled: Boolean = false,
        val migratedMemoryCount: Int = 0,
        val maintenanceJobs: List<MemoryMaintenanceJob> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadMemories()
    }

    fun loadMemories() {
        viewModelScope.launch {
            val migratedCount = memoryRepository.migrateActiveMemoriesToMarkdown()
            _uiState.update {
                it.copy(
                    markdown = memoryRepository.getLongTermMarkdown(),
                    memoryEnabled = settingRepository.fetchMemoryEnabled(),
                    migratedMemoryCount = migratedCount,
                    maintenanceJobs = memoryRepository.getMaintenanceJobs()
                )
            }
        }
    }

    fun exportMarkdown() {
        viewModelScope.launch {
            val markdown = _uiState.value.markdown.ifBlank { memoryRepository.getLongTermMarkdown() }
            _uiState.update { it.copy(exportMarkdown = markdown) }
        }
    }

    fun closeExport() {
        _uiState.update { it.copy(exportMarkdown = null) }
    }

    fun retryMaintenanceJob(jobId: String) {
        viewModelScope.launch {
            memoryRepository.retryMaintenanceJob(jobId)
            loadMemories()
        }
    }

    fun dismissMaintenanceJob(jobId: String) {
        viewModelScope.launch {
            memoryRepository.dismissMaintenanceJob(jobId)
            loadMemories()
        }
    }
}
