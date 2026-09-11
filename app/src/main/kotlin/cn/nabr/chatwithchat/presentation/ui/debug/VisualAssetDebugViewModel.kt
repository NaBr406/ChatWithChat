package cn.nabr.chatwithchat.presentation.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.nabr.chatwithchat.data.visual.VisualAssetDiagnostic
import cn.nabr.chatwithchat.data.visual.VisualAssetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VisualAssetDebugUiState(
    val isLoading: Boolean = true,
    val assets: List<VisualAssetDiagnostic> = emptyList(),
    val errorCode: String? = null
)

@HiltViewModel
class VisualAssetDebugViewModel @Inject constructor(
    private val repository: VisualAssetRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(VisualAssetDebugUiState())
    val uiState: StateFlow<VisualAssetDebugUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorCode = null)
            _uiState.value = runCatching { repository.listDiagnostics() }
                .fold(
                    onSuccess = { assets ->
                        VisualAssetDebugUiState(isLoading = false, assets = assets)
                    },
                    onFailure = { throwable ->
                        VisualAssetDebugUiState(
                            isLoading = false,
                            errorCode = throwable.message ?: "visual_asset_diagnostics_failed"
                        )
                    }
                )
        }
    }
}
