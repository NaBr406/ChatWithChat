package cn.nabr.chatwithchat.presentation.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryCodec
import cn.nabr.chatwithchat.data.memory.MemoryLongTermConsolidationRunResult
import cn.nabr.chatwithchat.data.memory.MemoryLongTermConsolidationRunner
import cn.nabr.chatwithchat.data.memory.MemoryLongTermConsolidationScheduler
import cn.nabr.chatwithchat.data.memory.MemoryLongTermPlanResult
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobStatus
import cn.nabr.chatwithchat.data.memory.MemoryModelPreference
import cn.nabr.chatwithchat.data.memory.MemoryModelResolver
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.repository.MemoryRepository
import cn.nabr.chatwithchat.data.repository.SettingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val settingRepository: SettingRepository,
    private val memoryModelResolver: MemoryModelResolver,
    private val memoryLongTermConsolidationScheduler: MemoryLongTermConsolidationScheduler? = null,
    memoryActivityLogDao: MemoryActivityLogDao,
    private val memoryLongTermConsolidationRunner: MemoryLongTermConsolidationRunner? = null
) : ViewModel() {

    private val markdownMemoryCodec = MarkdownMemoryCodec()

    data class UiState(
        val markdown: String = "",
        val displayMarkdown: String = "",
        val exportMarkdown: String? = null,
        val memoryEnabled: Boolean = false,
        val memoryModelPreference: MemoryModelPreference = MemoryModelPreference.Auto,
        val memoryModelOptions: List<MemoryModelOption> = emptyList(),
        val isMemoryModelLoading: Boolean = false,
        val isMemoryModelSaving: Boolean = false,
        val isMemoryModelPickerOpen: Boolean = false,
        val memoryModelError: MemoryModelUiError? = null,
        val isLongTermConsolidationScheduling: Boolean = false,
        val isForceLongTermConsolidationConfirmationOpen: Boolean = false,
        val activityLogs: List<MemoryActivityLog> = emptyList()
    )

    sealed interface Event {
        data class LongTermConsolidationFeedback(
            val result: LongTermConsolidationUiResult
        ) : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            memoryRepository.observeLongTermMarkdown().collect { markdown ->
                _uiState.update {
                    it.copy(
                        markdown = markdown,
                        displayMarkdown = markdownMemoryCodec.renderLongTermActiveProjection(markdown)
                    )
                }
            }
        }
        refreshMemoryEnabled()
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
            _uiState.update {
                it.copy(
                    markdown = markdown,
                    displayMarkdown = markdownMemoryCodec.renderLongTermActiveProjection(markdown),
                    exportMarkdown = markdown
                )
            }
        }
    }

    fun closeExport() {
        _uiState.update { it.copy(exportMarkdown = null) }
    }

    fun refreshMemoryEnabled() {
        viewModelScope.launch {
            val memoryEnabled = settingRepository.fetchMemoryEnabled()
            _uiState.update { it.copy(memoryEnabled = memoryEnabled) }
        }
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

    fun consolidateLongTermMemoryNow() {
        launchLongTermConsolidation(force = false)
    }

    fun requestForceLongTermConsolidation() {
        if (_uiState.value.isLongTermConsolidationScheduling) return
        _uiState.update { it.copy(isForceLongTermConsolidationConfirmationOpen = true) }
    }

    fun dismissForceLongTermConsolidationConfirmation() {
        _uiState.update { it.copy(isForceLongTermConsolidationConfirmationOpen = false) }
    }

    fun forceLongTermConsolidationNow() {
        if (_uiState.value.isLongTermConsolidationScheduling) return
        _uiState.update { it.copy(isForceLongTermConsolidationConfirmationOpen = false) }
        launchLongTermConsolidation(force = true)
    }

    private fun launchLongTermConsolidation(force: Boolean) {
        if (_uiState.value.isLongTermConsolidationScheduling) return
        _uiState.update { it.copy(isLongTermConsolidationScheduling = true) }
        viewModelScope.launch {
            _events.emit(
                Event.LongTermConsolidationFeedback(LongTermConsolidationUiResult.STARTED)
            )
            val result = try {
                withContext(Dispatchers.IO) {
                    if (force) {
                        memoryLongTermConsolidationRunner?.forceNow()
                    } else {
                        memoryLongTermConsolidationRunner?.runNow()
                    }
                        ?.toUiResult()
                        ?: if (force) {
                            memoryLongTermConsolidationScheduler?.scheduleForceNow()?.toUiResult()
                        } else {
                            memoryLongTermConsolidationScheduler?.scheduleNow()?.toUiResult()
                        }
                        ?: LongTermConsolidationUiResult.FAILED
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                LongTermConsolidationUiResult.FAILED
            } finally {
                _uiState.update { it.copy(isLongTermConsolidationScheduling = false) }
            }
            if (result != LongTermConsolidationUiResult.STARTED) {
                _events.emit(Event.LongTermConsolidationFeedback(result))
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

enum class LongTermConsolidationUiResult {
    STARTED,
    ALREADY_RUNNING,
    COMPLETED,
    MEMORY_DISABLED,
    FAILED
}

internal fun MemoryLongTermConsolidationRunResult.toUiResult(): LongTermConsolidationUiResult = when {
    plan.reason == MemoryLongTermConsolidationScheduler.REASON_MEMORY_DISABLED ||
        finalJobError == MemoryLongTermConsolidationScheduler.REASON_MEMORY_DISABLED ->
        LongTermConsolidationUiResult.MEMORY_DISABLED
    finalJobStatus == MemoryMaintenanceJobStatus.SUCCEEDED ->
        LongTermConsolidationUiResult.COMPLETED
    finalJobStatus in setOf(
        MemoryMaintenanceJobStatus.FAILED_TERMINAL,
        MemoryMaintenanceJobStatus.BLOCKED_DEPENDENCY,
        MemoryMaintenanceJobStatus.WAITING_REPAIR,
        MemoryMaintenanceJobStatus.DISMISSED
    ) -> LongTermConsolidationUiResult.FAILED
    plan.reason in setOf(
        MemoryLongTermConsolidationScheduler.REASON_ACTIVE_CHECKPOINT,
        MemoryLongTermConsolidationScheduler.REASON_COMPLETED_JOB_ACTIVE
    ) && process == null -> LongTermConsolidationUiResult.ALREADY_RUNNING
    finalJobStatus in setOf(
        MemoryMaintenanceJobStatus.PENDING,
        MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
        MemoryMaintenanceJobStatus.RUNNING
    ) -> LongTermConsolidationUiResult.STARTED
    process?.succeededCount == 1 && process.retryableCount == 0 ->
        LongTermConsolidationUiResult.STARTED
    process?.processedCount == 0 -> LongTermConsolidationUiResult.STARTED
    else -> LongTermConsolidationUiResult.FAILED
}

private fun MemoryLongTermPlanResult.toUiResult(): LongTermConsolidationUiResult = when {
    reason == MemoryLongTermConsolidationScheduler.REASON_MEMORY_DISABLED ->
        LongTermConsolidationUiResult.MEMORY_DISABLED
    reason in setOf(
        MemoryLongTermConsolidationScheduler.REASON_ACTIVE_CHECKPOINT,
        MemoryLongTermConsolidationScheduler.REASON_COMPLETED_JOB_ACTIVE
    ) -> LongTermConsolidationUiResult.ALREADY_RUNNING
    scheduled -> LongTermConsolidationUiResult.STARTED
    else -> LongTermConsolidationUiResult.FAILED
}

private fun AvailableChatModel.toMemoryModelOption(): MemoryModelOption = MemoryModelOption(
    platformUid = platformUid,
    modelId = modelId,
    platformName = platformName,
    modelName = displayName
)
