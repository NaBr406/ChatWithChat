package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AvailableChatModel
import dev.chungjungsoo.gptmobile.data.model.LastSelectedModel
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.model.coerceReasoningModeForModel
import dev.chungjungsoo.gptmobile.data.model.defaultReasoningMode
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    data class ChatListState(
        val chats: List<ChatRoomV2> = listOf(),
        val isSelectionMode: Boolean = false,
        val isSearchMode: Boolean = false,
        val selectedChats: List<Boolean> = listOf()
    )

    private val _chatListState = MutableStateFlow(ChatListState())
    val chatListState: StateFlow<ChatListState> = _chatListState.asStateFlow()

    private val _platformState = MutableStateFlow(listOf<PlatformV2>())
    val platformState = _platformState.asStateFlow()

    private val _lastSelectedModel = MutableStateFlow<LastSelectedModel?>(null)
    val lastSelectedModel = _lastSelectedModel.asStateFlow()

    private val _availableChatModels = MutableStateFlow(listOf<AvailableChatModel>())
    val availableChatModels = _availableChatModels.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _showDeleteWarningDialog = MutableStateFlow(false)
    val showDeleteWarningDialog: StateFlow<Boolean> = _showDeleteWarningDialog.asStateFlow()

    init {
        // Set up debounced search
        _searchQuery
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { query -> searchChats(query) }
            .launchIn(viewModelScope)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.update { query }
    }

    private fun searchChats(query: String) {
        viewModelScope.launch {
            val chats = chatRepository.searchChatsV2(query)
            _chatListState.update {
                it.copy(
                    chats = chats,
                    selectedChats = List(chats.size) { false }
                )
            }
        }
    }

    fun openDeleteWarningDialog() {
        _showDeleteWarningDialog.update { true }
    }

    fun closeDeleteWarningDialog() {
        _showDeleteWarningDialog.update { false }
    }

    fun deleteSelectedChats() {
        viewModelScope.launch {
            val selectedChats = _chatListState.value.chats.filterIndexed { index, _ ->
                _chatListState.value.selectedChats[index]
            }

            chatRepository.deleteChatsV2(selectedChats)
            _chatListState.update { it.copy(chats = chatRepository.fetchChatListV2()) }
            disableSelectionMode()
        }
    }

    fun duplicateSelectedChat() {
        viewModelScope.launch {
            val selectedChats = _chatListState.value.chats.filterIndexed { index, _ ->
                _chatListState.value.selectedChats[index]
            }
            val selectedChat = selectedChats.singleOrNull() ?: return@launch

            chatRepository.duplicateChatV2(selectedChat)
            _chatListState.update { it.copy(chats = chatRepository.fetchChatListV2()) }
            disableSelectionMode()
        }
    }

    fun disableSelectionMode() {
        _chatListState.update {
            it.copy(
                selectedChats = List(it.chats.size) { false },
                isSelectionMode = false
            )
        }
    }

    fun disableSearchMode() {
        _chatListState.update { it.copy(isSearchMode = false) }
        _searchQuery.update { "" }
    }

    fun enableSelectionMode() {
        disableSearchMode()
        _chatListState.update { it.copy(isSelectionMode = true) }
    }

    fun enableSearchMode() {
        disableSelectionMode()
        _chatListState.update { it.copy(isSearchMode = true) }
    }

    fun fetchChats() {
        viewModelScope.launch {
            val chats = chatRepository.fetchChatListV2()

            _chatListState.update {
                it.copy(
                    chats = chats,
                    selectedChats = List(chats.size) { false },
                    isSelectionMode = false
                )
            }

            Log.d("chats", "${_chatListState.value.chats}")
        }
    }

    fun fetchPlatformStatus() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            val availableModels = settingRepository.fetchEnabledChatModels()
            val lastSelectedModel = settingRepository.fetchLastSelectedModel()
            _platformState.update { platforms }
            _availableChatModels.update { availableModels }
            _lastSelectedModel.update {
                selectUsableLastSelectedModel(
                    availableModels = availableModels,
                    lastSelectedModel = lastSelectedModel,
                    defaultModel = settingRepository.resolveDefaultChatModel()
                )
            }
        }
    }

    fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode? = null) {
        val sanitizedModel = model.trim()
        if (platformUid.isBlank() || sanitizedModel.isBlank()) return

        val selectedModel = _availableChatModels.value.firstOrNull { availableModel ->
            availableModel.platformUid == platformUid && availableModel.modelId == sanitizedModel
        }
        val nextReasoningMode = selectedModel?.let { availableModel ->
            availableModel.platform.coerceReasoningModeForModel(
                reasoningMode ?: _lastSelectedModel.value?.reasoningMode ?: availableModel.platform.defaultReasoningMode(),
                availableModel.modelId
            )
        } ?: (reasoningMode ?: ReasoningMode.AUTO)

        _lastSelectedModel.update {
            LastSelectedModel(
                platformUid = platformUid,
                model = sanitizedModel,
                reasoningMode = nextReasoningMode
            )
        }
        viewModelScope.launch {
            settingRepository.updateLastSelectedModel(platformUid, sanitizedModel, nextReasoningMode)
        }
    }

    fun updateLastSelectedReasoningMode(reasoningMode: ReasoningMode) {
        val selectedModel = _lastSelectedModel.value ?: _availableChatModels.value.firstOrNull()?.let { availableModel ->
            LastSelectedModel(
                platformUid = availableModel.platformUid,
                model = availableModel.modelId,
                reasoningMode = availableModel.platform.defaultReasoningMode()
            )
        } ?: return

        updateLastSelectedModel(selectedModel.platformUid, selectedModel.model, reasoningMode)
    }

    fun selectChat(chatRoomIdx: Int) {
        if (chatRoomIdx < 0 || chatRoomIdx >= _chatListState.value.chats.size) return

        _chatListState.update {
            it.copy(
                selectedChats = it.selectedChats.mapIndexed { index, b ->
                    if (index == chatRoomIdx) {
                        !b
                    } else {
                        b
                    }
                }
            )
        }

        if (_chatListState.value.selectedChats.count { it } == 0) {
            disableSelectionMode()
        }
    }

    private fun selectUsableLastSelectedModel(
        availableModels: List<AvailableChatModel>,
        lastSelectedModel: LastSelectedModel?,
        defaultModel: AvailableChatModel?
    ): LastSelectedModel? {
        lastSelectedModel?.let { lastSelected ->
            availableModels.firstOrNull { model ->
                model.platformUid == lastSelected.platformUid && model.modelId == lastSelected.model
            }?.let { model ->
                return LastSelectedModel(
                    platformUid = model.platformUid,
                    model = model.modelId,
                    reasoningMode = model.platform.coerceReasoningModeForModel(lastSelected.reasoningMode, model.modelId)
                )
            }
        }

        return defaultModel?.let { model ->
            LastSelectedModel(
                platformUid = model.platformUid,
                model = model.modelId,
                reasoningMode = model.platform.coerceReasoningModeForModel(model.platform.defaultReasoningMode(), model.modelId)
            )
        } ?: availableModels.firstOrNull()?.let { model ->
            LastSelectedModel(
                platformUid = model.platformUid,
                model = model.modelId,
                reasoningMode = model.platform.coerceReasoningModeForModel(model.platform.defaultReasoningMode(), model.modelId)
            )
        }
    }
}
