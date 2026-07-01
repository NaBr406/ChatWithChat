package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingViewModelV2 @Inject constructor(
    private val settingRepository: SettingRepository
) : ViewModel() {

    private val _platformState = MutableStateFlow(listOf<PlatformV2>())
    val platformState: StateFlow<List<PlatformV2>> = _platformState.asStateFlow()

    private val _memoryEnabled = MutableStateFlow(false)
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    private val _modelManagementState = MutableStateFlow(ModelManagementState())
    val modelManagementState: StateFlow<ModelManagementState> = _modelManagementState.asStateFlow()

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    init {
        fetchPlatforms()
        fetchMemoryEnabled()
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

    data class ModelManagementState(
        val platforms: List<PlatformV2> = emptyList(),
        val modelsByPlatformUid: Map<String, List<PlatformModelV2>> = emptyMap(),
        val refreshingPlatformUids: Set<String> = emptySet()
    )
}
