package cn.nabr.chatwithchat.presentation.ui.memory

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryCodec
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryEntry
import cn.nabr.chatwithchat.data.memory.MemoryCompletedTurnInput
import cn.nabr.chatwithchat.data.memory.MemoryFilePaths
import cn.nabr.chatwithchat.data.memory.MemoryFileStore
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobFamily
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobStatus
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobType
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkEnqueuer
import cn.nabr.chatwithchat.data.memory.MemoryModelPreference
import cn.nabr.chatwithchat.data.memory.MemoryModelResolver
import cn.nabr.chatwithchat.data.memory.MemoryMutationCommitResult
import cn.nabr.chatwithchat.data.memory.MemoryMutationCoordinator
import cn.nabr.chatwithchat.data.memory.MemoryMutationTarget
import cn.nabr.chatwithchat.data.memory.MemoryPromptBuilder
import cn.nabr.chatwithchat.data.memory.MemorySensitivity
import cn.nabr.chatwithchat.data.memory.MemorySource
import cn.nabr.chatwithchat.data.memory.MemoryTurnRecordingResult
import cn.nabr.chatwithchat.data.memory.PreparedMemoryContext
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.repository.MemoryRepository
import cn.nabr.chatwithchat.data.repository.MemoryRepositoryImpl
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.io.File
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryViewModelInstrumentedTest {

    @Test
    fun indexSchedulingFailure_canonicalRevisionRefreshesViewModelAndExportReadsFreshFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val databaseName = "memory-view-model-$suffix.db"
        val memoryRoot = File(context.filesDir, "memory_view_model_test/$suffix")
        context.deleteDatabase(databaseName)
        memoryRoot.deleteRecursively()

        val database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, databaseName).build()
        val fileStore = MemoryFileStore(MemoryFilePaths(memoryRoot), FIXED_CLOCK)
        fileStore.ensureStore().getOrThrow()
        val codec = MarkdownMemoryCodec()
        val initial = codec.renderLongTerm(
            listOf(memoryEntry("Canonical initial content", updatedAt = 1L))
        )
        val committed = codec.renderLongTerm(
            listOf(memoryEntry("Canonical commit survives index scheduling failure", updatedAt = 2L))
        )
        val freshExport = "# ChatWithChat Memory\n\n- Canonical content newer than the UI revision\n"
        fileStore.replaceLongTermMemory(initial).getOrThrow()

        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryFileStore = fileStore
        )
        val settings = settingRepository(memoryEnabled = true)
        val viewModel = MemoryViewModel(
            memoryRepository = repository,
            settingRepository = settings,
            memoryModelResolver = MemoryModelResolver(settings),
            memoryActivityLogDao = EmptyMemoryActivityLogDao
        )
        val maintenanceScheduler = MemoryMaintenanceScheduler(database.memoryMaintenanceJobDao(), FIXED_CLOCK)
        val failingWorkEnqueuer = IndexFailingWorkEnqueuer()
        val mutationCoordinator = MemoryMutationCoordinator(
            recoveryDao = database.memoryRecoveryDao(),
            memoryFileStore = fileStore,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = failingWorkEnqueuer,
            clock = FIXED_CLOCK
        )

        try {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> state.markdown == initial && state.memoryEnabled }
            }
            val revisionBeforeCommit = fileStore.longTermRevision.value
            val mutation = mutationCoordinator.prepareLocalMutation(
                operationKey = "memory-view-model-index-failure-$suffix",
                targets = listOf(
                    MemoryMutationTarget(
                        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                        baseContent = initial,
                        targetContent = committed,
                        targetIndexFingerprint = VALID_INDEX_FINGERPRINT
                    )
                )
            )

            val result = mutationCoordinator.reconcile(mutation)

            assertTrue(result is MemoryMutationCommitResult.CanonicalCommitted)
            assertTrue((result as MemoryMutationCommitResult.CanonicalCommitted).hasPendingIndex)
            assertEquals(committed, fileStore.readLongTermMemory().getOrThrow())
            assertEquals(revisionBeforeCommit + 1L, fileStore.longTermRevision.value)
            assertEquals(1, failingWorkEnqueuer.indexSchedulingAttempts)
            assertEquals(
                1,
                database.memoryMaintenanceJobDao().getByTypeAndStatuses(
                    type = MemoryMaintenanceJobType.SYNC_VECTOR_INDEX,
                    statuses = listOf(MemoryMaintenanceJobStatus.PENDING)
                ).size
            )
            withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> state.markdown == committed }
            }

            val revisionBeforeUnobservedWrite = fileStore.longTermRevision.value
            File(memoryRoot, MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME).writeText(freshExport, Charsets.UTF_8)
            assertEquals(revisionBeforeUnobservedWrite, fileStore.longTermRevision.value)
            assertEquals(committed, viewModel.uiState.value.markdown)
            viewModel.exportMarkdown()

            val exported = withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> state.exportMarkdown == freshExport }
            }
            assertEquals(freshExport, exported.markdown)
        } finally {
            viewModel.viewModelScope.cancel()
            database.close()
            context.deleteDatabase(databaseName)
            memoryRoot.deleteRecursively()
        }
    }

    @Test
    fun openView_observesCanonicalOnceAndExportsFreshContent() = runBlocking {
        val initial = "# ChatWithChat Memory\n\n- Initial content\n"
        val background = "# ChatWithChat Memory\n\n- Background commit\n"
        val stale = "# ChatWithChat Memory\n\n- Temporarily stale UI\n"
        val freshExport = "# ChatWithChat Memory\n\n- Fresh export content\n"
        val repository = RecordingMemoryRepository(initial)
        val settings = settingRepository(memoryEnabled = true)
        val viewModel = MemoryViewModel(
            memoryRepository = repository,
            settingRepository = settings,
            memoryModelResolver = MemoryModelResolver(settings),
            memoryActivityLogDao = EmptyMemoryActivityLogDao
        )

        try {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> state.markdown == initial && state.memoryEnabled }
            }
            assertEquals(1, repository.observationSubscriptions)

            repository.observedMarkdown.value = background
            withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> state.markdown == background }
            }
            assertEquals(1, repository.observationSubscriptions)

            repository.observedMarkdown.value = stale
            withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> state.markdown == stale }
            }
            repository.freshMarkdown = freshExport
            viewModel.exportMarkdown()

            val exported = withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> state.exportMarkdown == freshExport }
            }
            assertEquals(freshExport, exported.markdown)
            assertEquals(1, repository.freshReadCount)
            assertEquals(1, repository.observationSubscriptions)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun memoryModelPicker_preservesUnavailableFixedAndSavesExactDuplicatePair() = runBlocking {
        val originalPreference = MemoryModelPreference.Fixed("missing-platform", "shared-model")
        val options = listOf(
            availableModel("first-platform", "First", "shared-model"),
            availableModel("second-platform", "Second", "shared-model")
        )
        var savedPreference: MemoryModelPreference? = null
        val settings = settingRepository(
            memoryEnabled = true,
            memoryModelPreference = originalPreference,
            availableModels = { options },
            onMemoryModelPreferenceUpdated = { preference -> savedPreference = preference }
        )
        val viewModel = MemoryViewModel(
            memoryRepository = RecordingMemoryRepository(""),
            settingRepository = settings,
            memoryModelResolver = MemoryModelResolver(settings),
            memoryActivityLogDao = EmptyMemoryActivityLogDao
        )

        try {
            val loaded = withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> !state.isMemoryModelLoading && state.memoryModelOptions.size == 2 }
            }
            assertEquals(originalPreference, loaded.memoryModelPreference)
            assertEquals(listOf("first-platform", "second-platform"), loaded.memoryModelOptions.map { it.platformUid })

            viewModel.openMemoryModelPicker()
            assertTrue(viewModel.uiState.value.isMemoryModelPickerOpen)
            val selected = MemoryModelPreference.Fixed("second-platform", "shared-model")
            viewModel.selectMemoryModel(selected)

            val saved = withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state ->
                    !state.isMemoryModelSaving && !state.isMemoryModelPickerOpen && state.memoryModelPreference == selected
                }
            }
            assertEquals(selected, savedPreference)
            assertEquals(selected, saved.memoryModelPreference)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun memoryModelRefresh_filtersUncredentialedAndSaveFailureRetainsSelection() = runBlocking {
        val valid = availableModel("valid-platform", "Valid", "model")
        val uncredentialed = availableModel("missing-token", "Missing token", "model", token = " ")
        var catalog = emptyList<AvailableChatModel>()
        val settings = settingRepository(
            memoryEnabled = true,
            availableModels = { catalog },
            onMemoryModelPreferenceUpdated = { error("simulated save failure") }
        )
        val viewModel = MemoryViewModel(
            memoryRepository = RecordingMemoryRepository(""),
            settingRepository = settings,
            memoryModelResolver = MemoryModelResolver(settings),
            memoryActivityLogDao = EmptyMemoryActivityLogDao
        )

        try {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state -> !state.isMemoryModelLoading }
            }
            catalog = listOf(uncredentialed, valid)
            viewModel.refreshMemoryModels()
            val refreshed = withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state ->
                    !state.isMemoryModelLoading && state.memoryModelOptions.map { it.platformUid } == listOf("valid-platform")
                }
            }
            assertEquals(MemoryModelPreference.Auto, refreshed.memoryModelPreference)

            viewModel.openMemoryModelPicker()
            viewModel.selectMemoryModel(MemoryModelPreference.Fixed(valid.platformUid, valid.modelId))
            val failed = withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.uiState.first { state ->
                    !state.isMemoryModelSaving && state.memoryModelError == MemoryModelUiError.SAVE_FAILED
                }
            }
            assertEquals(MemoryModelPreference.Auto, failed.memoryModelPreference)
            assertTrue(failed.isMemoryModelPickerOpen)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private class RecordingMemoryRepository(initialMarkdown: String) : MemoryRepository {
        val observedMarkdown = MutableStateFlow(initialMarkdown)
        var freshMarkdown: String = initialMarkdown
        var observationSubscriptions: Int = 0
        var freshReadCount: Int = 0

        override suspend fun onMemoryEnabledChanged(enabled: Boolean) = Unit

        override suspend fun recordUserActivity(chatId: Int, activityAt: Long) = Unit

        override suspend fun recordCompletedTurn(input: MemoryCompletedTurnInput): MemoryTurnRecordingResult =
            MemoryTurnRecordingResult.skipped("view_model_test")

        override suspend fun prepareMemoryContext(
            chatRoom: ChatRoomV2,
            userMessages: List<MessageV2>,
            assistantMessages: List<List<MessageV2>>,
            memoryPlatform: PlatformV2?
        ): PreparedMemoryContext = PreparedMemoryContext()

        override suspend fun getLongTermMarkdown(): String {
            freshReadCount += 1
            return freshMarkdown
        }

        override fun observeLongTermMarkdown(): Flow<String> = observedMarkdown.onStart {
            observationSubscriptions += 1
        }
    }

    private data object EmptyMemoryActivityLogDao : MemoryActivityLogDao {
        override fun observeLatest(limit: Int): Flow<List<MemoryActivityLog>> = flowOf(emptyList())

        override suspend fun upsert(log: MemoryActivityLog) = Unit

        override suspend fun insertRun(log: MemoryActivityLog): Long = -1

        override suspend fun getById(activityRunId: String): MemoryActivityLog? = null

        override suspend fun getActiveJobRuns(limit: Int): List<MemoryActivityLog> = emptyList()

        override suspend fun getRun(jobId: String, retryCycle: Int, attempt: Int): MemoryActivityLog? = null

        override suspend fun advanceRun(
            activityRunId: String,
            expectedRowVersion: Long,
            expectedPhase: String,
            nextPhase: String,
            platformUid: String?,
            modelId: String?,
            platformName: String?,
            modelName: String?,
            inputCount: Int?,
            operationCount: Int?,
            phaseSummaryJson: String,
            updatedAt: Long
        ): Int = 0

        override suspend fun finishRun(
            activityRunId: String,
            expectedRowVersion: Long,
            expectedPhase: String,
            status: String,
            platformUid: String?,
            modelId: String?,
            platformName: String?,
            modelName: String?,
            inputCount: Int?,
            operationCount: Int?,
            errorCode: String?,
            detail: String?,
            phaseSummaryJson: String,
            completedAt: Long,
            updatedAt: Long
        ): Int = 0

        override suspend fun finish(
            logId: String,
            status: String,
            detail: String?,
            operationCount: Int?,
            completedAt: Long,
            updatedAt: Long
        ) = Unit

        override suspend fun deleteOlderThan(before: Long): Int = 0
    }

    private fun settingRepository(
        memoryEnabled: Boolean,
        memoryModelPreference: MemoryModelPreference = MemoryModelPreference.Auto,
        availableModels: () -> List<AvailableChatModel> = { emptyList() },
        onMemoryModelPreferenceUpdated: (MemoryModelPreference) -> Unit = {}
    ): SettingRepository =
        Proxy.newProxyInstance(
            SettingRepository::class.java.classLoader,
            arrayOf(SettingRepository::class.java)
        ) { proxy, method, arguments ->
            when (method.name) {
                "fetchMemoryEnabled" -> memoryEnabled
                "fetchMemoryModelPreference" -> memoryModelPreference
                "fetchEnabledChatModels" -> availableModels()
                "updateMemoryModelPreference" -> {
                    onMemoryModelPreferenceUpdated(arguments?.first() as MemoryModelPreference)
                    Unit
                }
                "toString" -> "MemoryViewModelInstrumentedTest.SettingRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected SettingRepository call: ${method.name}")
            }
        } as SettingRepository

    private fun availableModel(
        platformUid: String,
        platformName: String,
        modelId: String,
        token: String? = "token"
    ): AvailableChatModel =
        AvailableChatModel(
            platform = PlatformV2(
                uid = platformUid,
                name = platformName,
                compatibleType = ClientType.OPENAI,
                enabled = true,
                apiUrl = "https://example.test/v1",
                token = token,
                model = modelId
            ),
            model = PlatformModelV2(
                platformUid = platformUid,
                modelId = modelId,
                displayName = modelId,
                enabled = true
            )
        )

    private fun memoryEntry(text: String, updatedAt: Long): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = "mem_view_model_index_fixture",
        text = text,
        type = "stable_profile",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    private class IndexFailingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
        var indexSchedulingAttempts: Int = 0

        override fun enqueueWork(family: String, delaySeconds: Long) {
            if (family == MemoryMaintenanceJobFamily.INDEX) {
                indexSchedulingAttempts += 1
                error("Simulated index scheduling failure")
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)
        val VALID_INDEX_FINGERPRINT: String = "a".repeat(64)
    }
}
