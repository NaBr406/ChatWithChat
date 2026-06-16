package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    data class UiState(
        val memories: List<PersonalMemory> = emptyList(),
        val editingMemory: PersonalMemory? = null,
        val editedContent: String = "",
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refreshMemories()
    }

    fun refreshMemories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _uiState.update {
                it.copy(
                    memories = memoryRepository.getAllMemories(),
                    isLoading = false
                )
            }
        }
    }

    fun startEditing(memory: PersonalMemory) {
        _uiState.update {
            it.copy(
                editingMemory = memory,
                editedContent = memory.content
            )
        }
    }

    fun updateEditedContent(content: String) {
        _uiState.update { it.copy(editedContent = content) }
    }

    fun dismissEditor() {
        _uiState.update {
            it.copy(
                editingMemory = null,
                editedContent = ""
            )
        }
    }

    fun saveEditedMemory() {
        val memory = _uiState.value.editingMemory ?: return
        val content = _uiState.value.editedContent.trim()
        if (content.isBlank()) return

        viewModelScope.launch {
            memoryRepository.updateMemoryContent(memory.id, content)
            dismissEditor()
            refreshMemories()
        }
    }

    fun markResolved(memoryId: Int) {
        viewModelScope.launch {
            memoryRepository.markMemoryResolved(memoryId)
            refreshMemories()
        }
    }

    fun archive(memoryId: Int) {
        viewModelScope.launch {
            memoryRepository.archiveMemory(memoryId)
            refreshMemories()
        }
    }

    fun delete(memoryId: Int) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memoryId)
            refreshMemories()
        }
    }
}
