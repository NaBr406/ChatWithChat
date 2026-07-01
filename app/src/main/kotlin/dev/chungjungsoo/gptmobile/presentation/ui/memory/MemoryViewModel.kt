package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
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
        val memories: List<PersonalMemory> = emptyList(),
        val editingMemory: PersonalMemory? = null,
        val exportMarkdown: String? = null,
        val memoryEnabled: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadMemories()
    }

    fun loadMemories() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    memories = memoryRepository.getMemories(),
                    memoryEnabled = settingRepository.fetchMemoryEnabled()
                )
            }
        }
    }

    fun openEdit(memory: PersonalMemory) {
        _uiState.update { it.copy(editingMemory = memory) }
    }

    fun closeEdit() {
        _uiState.update { it.copy(editingMemory = null) }
    }

    fun saveEdit(summary: String, recallText: String) {
        val memory = _uiState.value.editingMemory ?: return
        viewModelScope.launch {
            memoryRepository.updateMemory(
                memory.copy(
                    summary = summary.trim(),
                    recallText = recallText.trim()
                )
            )
            _uiState.update { it.copy(editingMemory = null) }
            loadMemories()
        }
    }

    fun confirm(memory: PersonalMemory) = updateThenReload { memoryRepository.confirmMemory(memory) }

    fun reject(memory: PersonalMemory) = updateThenReload { memoryRepository.rejectMemory(memory) }

    fun delete(memory: PersonalMemory) = updateThenReload { memoryRepository.deleteMemory(memory) }

    fun markResolved(memory: PersonalMemory) = updateThenReload { memoryRepository.markResolved(memory) }

    fun archive(memory: PersonalMemory) = updateThenReload { memoryRepository.archiveMemory(memory) }

    fun exportMarkdown() {
        viewModelScope.launch {
            _uiState.update { it.copy(exportMarkdown = memoryRepository.exportMarkdown()) }
        }
    }

    fun closeExport() {
        _uiState.update { it.copy(exportMarkdown = null) }
    }

    private fun updateThenReload(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            loadMemories()
        }
    }
}
