package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryActivityLogDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryActivityLog
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobFamily
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobStatus
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobType
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkEnqueuer
import dev.chungjungsoo.gptmobile.data.memory.MemoryMutationCommitResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryMutationCoordinator
import dev.chungjungsoo.gptmobile.data.memory.MemoryMutationTarget
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnRecordingResult
import dev.chungjungsoo.gptmobile.data.memory.PreparedMemoryContext
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepositoryImpl
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
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
        val initial = "# ChatWithChat Memory\n\n- Canonical initial content\n"
        val committed = "# ChatWithChat Memory\n\n- Canonical commit survives index scheduling failure\n"
        val freshExport = "# ChatWithChat Memory\n\n- Canonical content newer than the UI revision\n"
        fileStore.replaceLongTermMemory(initial).getOrThrow()

        val repository = MemoryRepositoryImpl(
            memoryPromptBuilder = MemoryPromptBuilder(),
            memoryFileStore = fileStore
        )
        val viewModel = MemoryViewModel(
            memoryRepository = repository,
            settingRepository = settingRepository(memoryEnabled = true),
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
        val viewModel = MemoryViewModel(
            memoryRepository = repository,
            settingRepository = settingRepository(memoryEnabled = true),
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

    private fun settingRepository(memoryEnabled: Boolean): SettingRepository =
        Proxy.newProxyInstance(
            SettingRepository::class.java.classLoader,
            arrayOf(SettingRepository::class.java)
        ) { proxy, method, arguments ->
            when (method.name) {
                "fetchMemoryEnabled" -> memoryEnabled
                "toString" -> "MemoryViewModelInstrumentedTest.SettingRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected SettingRepository call: ${method.name}")
            }
        } as SettingRepository

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
