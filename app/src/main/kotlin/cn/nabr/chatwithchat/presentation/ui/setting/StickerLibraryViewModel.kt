package cn.nabr.chatwithchat.presentation.ui.setting

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cn.nabr.chatwithchat.data.sticker.StickerCatalogItem
import cn.nabr.chatwithchat.data.sticker.StickerItemMetadata
import cn.nabr.chatwithchat.data.sticker.StickerRepository
import cn.nabr.chatwithchat.data.repository.SettingRepository
import cn.nabr.chatwithchat.presentation.ui.chat.StickerAssetResolver
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class StickerLibraryViewModel @Inject constructor(
    private val stickerRepository: StickerRepository,
    private val settingRepository: SettingRepository
) : ViewModel(), StickerAssetResolver {
    data class UiState(
        val stickers: List<StickerCatalogItem> = emptyList(),
        val isCatalogLoading: Boolean = true,
        val isCatalogUnavailable: Boolean = false,
        val automaticStickerRepliesEnabled: Boolean = true,
        val isImporting: Boolean = false,
        val importProgress: Int = 0,
        val importTotal: Int = 0
    ) {
        val customStickers: List<StickerCatalogItem>
            get() = stickers.filterNot(StickerCatalogItem::isBuiltin)

        val builtInStickers: List<StickerCatalogItem>
            get() = stickers.filter(StickerCatalogItem::isBuiltin)
    }

    sealed interface Event {
        data class ImportFinished(val importedCount: Int, val rejectedCount: Int) : Event

        data object MetadataUpdated : Event

        data object ItemDeleted : Event

        data object MutationFailed : Event

        data object CatalogUnavailable : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    init {
        observeCatalog()
        fetchAutomaticStickerRepliesEnabled()
    }

    fun importStaticImages(uris: List<Uri>) {
        val uniqueUris = uris.distinct()
        if (uniqueUris.isEmpty() || _uiState.value.isImporting) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgress = 0,
                    importTotal = uniqueUris.size
                )
            }

            var importedCount = 0
            var rejectedCount = 0
            uniqueUris.forEachIndexed { index, uri ->
                try {
                    val result = stickerRepository.importStaticImages(listOf(uri))
                    importedCount += result.imported.size
                    rejectedCount += result.rejected.size
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    rejectedCount += 1
                } finally {
                    _uiState.update { state -> state.copy(importProgress = index + 1) }
                }
            }

            _uiState.update { state -> state.copy(isImporting = false) }
            _events.emit(
                Event.ImportFinished(
                    importedCount = importedCount,
                    rejectedCount = rejectedCount
                )
            )
        }
    }

    fun updateCustomItem(
        stickerId: String,
        title: String,
        altText: String,
        tags: List<String>
    ) {
        viewModelScope.launch {
            val updated = stickerRepository.updateCustomItem(
                stickerId = stickerId,
                metadata = StickerItemMetadata(
                    title = title,
                    altText = altText,
                    tags = tags
                )
            )
            _events.emit(if (updated) Event.MetadataUpdated else Event.MutationFailed)
        }
    }

    fun setCustomItemEnabled(stickerId: String, enabled: Boolean) {
        viewModelScope.launch {
            if (!stickerRepository.setCustomItemEnabled(stickerId, enabled)) {
                _events.emit(Event.MutationFailed)
            }
        }
    }

    fun deleteCustomItem(stickerId: String) {
        viewModelScope.launch {
            val deleted = stickerRepository.deleteCustomItem(stickerId)
            _events.emit(if (deleted) Event.ItemDeleted else Event.MutationFailed)
        }
    }

    fun updateAutomaticStickerRepliesEnabled(enabled: Boolean) {
        _uiState.update { state -> state.copy(automaticStickerRepliesEnabled = enabled) }
        viewModelScope.launch {
            settingRepository.updateAutomaticStickerRepliesEnabled(enabled)
        }
    }

    override suspend fun openStickerAsset(assetKey: String): InputStream? = stickerRepository.openAsset(assetKey)

    private fun observeCatalog() {
        viewModelScope.launch {
            stickerRepository.observeCatalog()
                .catch {
                    _uiState.update { state ->
                        state.copy(isCatalogLoading = false, isCatalogUnavailable = true)
                    }
                    _events.emit(Event.CatalogUnavailable)
                }
                .collect { stickers ->
                    _uiState.update {
                        it.copy(
                            stickers = stickers,
                            isCatalogLoading = false,
                            isCatalogUnavailable = false
                        )
                    }
                }
        }
    }

    private fun fetchAutomaticStickerRepliesEnabled() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    automaticStickerRepliesEnabled = settingRepository.fetchAutomaticStickerRepliesEnabled()
                )
            }
        }
    }
}
