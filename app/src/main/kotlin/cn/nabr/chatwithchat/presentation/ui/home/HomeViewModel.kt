package cn.nabr.chatwithchat.presentation.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.LastSelectedModel
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.coerceReasoningModeForModel
import cn.nabr.chatwithchat.data.model.defaultReasoningMode
import cn.nabr.chatwithchat.data.repository.ChatRepository
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

sealed interface HomeModelsState {
    data object Loading : HomeModelsState

    data class Ready(
        val models: List<AvailableChatModel>,
        val hasConfiguredPlatforms: Boolean = models.isNotEmpty(),
        val loadFailed: Boolean = false
    ) : HomeModelsState
}

internal fun HomeModelsState.modelsOrEmpty(): List<AvailableChatModel> = when (this) {
    HomeModelsState.Loading -> emptyList()
    is HomeModelsState.Ready -> models
}

internal fun HomeModelsState.canStartChat(): Boolean = this is HomeModelsState.Ready && models.isNotEmpty()

internal fun HomeModelsState.shouldShowAddProvider(): Boolean =
    this is HomeModelsState.Ready && models.isEmpty() && !hasConfiguredPlatforms && !loadFailed

@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val TAG = "HomeViewModel"
    }

    data class ChatListState(
        val chats: List<ChatRoomV2> = listOf(),
        val isSelectionMode: Boolean = false,
        val isSearchMode: Boolean = false,
        val selectedChats: List<Boolean> = listOf(),
        val showDeleteWarningDialog: Boolean = false
    )

    private val _chatListState = MutableStateFlow(ChatListState())
    val chatListState: StateFlow<ChatListState> = _chatListState.asStateFlow()

    private val _platformState = MutableStateFlow(listOf<PlatformV2>())
    val platformState = _platformState.asStateFlow()

    private val _lastSelectedModel = MutableStateFlow<LastSelectedModel?>(null)
    val lastSelectedModel = _lastSelectedModel.asStateFlow()

    private val _homeModelsState = MutableStateFlow<HomeModelsState>(HomeModelsState.Loading)
    val homeModelsState: StateFlow<HomeModelsState> = _homeModelsState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

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
        _chatListState.update { it.copy(showDeleteWarningDialog = true) }
    }

    fun closeDeleteWarningDialog() {
        _chatListState.update { it.copy(showDeleteWarningDialog = false) }
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
        resetDrawerSelection()
    }

    fun resetDrawerSelection() {
        _chatListState.update(::resetDrawerSelectionState)
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
            var platforms = emptyList<PlatformV2>()
            var availableModels = emptyList<AvailableChatModel>()
            var selectedModel: LastSelectedModel? = null
            var loadFailed = false
            try {
                platforms = settingRepository.fetchPlatformV2s()
                availableModels = settingRepository.fetchEnabledChatModels()
                val lastSelectedModel = settingRepository.fetchLastSelectedModel()
                selectedModel = selectUsableLastSelectedModel(
                    availableModels = availableModels,
                    lastSelectedModel = lastSelectedModel,
                    defaultModel = settingRepository.resolveDefaultChatModel()
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                loadFailed = true
                Log.e(TAG, "Failed to load home platform models", exception)
            }
            _platformState.value = platforms
            _lastSelectedModel.value = selectedModel
            _homeModelsState.value = HomeModelsState.Ready(
                models = availableModels,
                hasConfiguredPlatforms = platforms.isNotEmpty() || availableModels.isNotEmpty(),
                loadFailed = loadFailed
            )
        }
    }

    fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode? = null) {
        val sanitizedModel = model.trim()
        if (platformUid.isBlank() || sanitizedModel.isBlank()) return

        val selectedModel = _homeModelsState.value.modelsOrEmpty().firstOrNull { availableModel ->
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
        val selectedModel = _lastSelectedModel.value ?: _homeModelsState.value.modelsOrEmpty().firstOrNull()?.let { availableModel ->
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

internal fun resetDrawerSelectionState(state: HomeViewModel.ChatListState): HomeViewModel.ChatListState = state.copy(
    selectedChats = List(state.chats.size) { false },
    isSelectionMode = false,
    showDeleteWarningDialog = false
)

internal fun shouldResetSelectionAfterDrawerTransition(
    wasOpenOrOpening: Boolean,
    isFullyClosed: Boolean
): Boolean = wasOpenOrOpening && isFullyClosed
