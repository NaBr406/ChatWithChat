package dev.chungjungsoo.gptmobile.presentation.ui.memory

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryActivityLogDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryActivityLog
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnRecordingResult
import dev.chungjungsoo.gptmobile.data.memory.PreparedMemoryContext
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryViewModelInstrumentedTest {

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

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
