package cn.nabr.chatwithchat.presentation.ui.memory

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.memory.MemoryActivityCategory
import cn.nabr.chatwithchat.data.memory.MemoryActivityPhase
import cn.nabr.chatwithchat.data.memory.MemoryActivityPhaseHistory
import cn.nabr.chatwithchat.data.memory.MemoryActivityStatus
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryActivityLogItemInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandingUnifiedRun_keepsOneTopLevelRow() {
        val phaseHistory = MemoryActivityPhaseHistory.start(
            phase = MemoryActivityPhase.MODEL_RESOLUTION,
            status = MemoryActivityStatus.RUNNING,
            startedAt = 10
        ).advance(
            expectedPhase = MemoryActivityPhase.MODEL_RESOLUTION,
            nextPhase = MemoryActivityPhase.MODEL_CALL,
            transitionedAt = 11
        ).advance(
            expectedPhase = MemoryActivityPhase.MODEL_CALL,
            nextPhase = MemoryActivityPhase.GENERATION,
            transitionedAt = 12
        ).advance(
            expectedPhase = MemoryActivityPhase.GENERATION,
            nextPhase = MemoryActivityPhase.ORGANIZATION,
            transitionedAt = 13
        ).finish(
            expectedPhase = MemoryActivityPhase.ORGANIZATION,
            status = MemoryActivityStatus.SUCCEEDED,
            completedAt = 14
        )
        val log = activityLog(
            phase = MemoryActivityPhase.ORGANIZATION,
            phaseSummaryJson = phaseHistory.encode()
        )

        composeRule.setContent {
            ChatWithChatTheme { MemoryActivityLogItem(log) }
        }

        composeRule.onAllNodesWithTag(MEMORY_ACTIVITY_ROW_TEST_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onAllNodesWithTag(MEMORY_ACTIVITY_PHASE_TEST_TAG, useUnmergedTree = true)
            .assertCountEquals(0)

        composeRule.onNodeWithTag(MEMORY_ACTIVITY_ROW_TEST_TAG, useUnmergedTree = true).performClick()

        composeRule.onAllNodesWithTag(MEMORY_ACTIVITY_ROW_TEST_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onAllNodesWithTag(MEMORY_ACTIVITY_PHASE_TEST_TAG, useUnmergedTree = true)
            .assertCountEquals(4)
    }

    @Test
    fun legacyTurnCount_keepsLegacyLabel() {
        val log = activityLog(
            category = MemoryActivityCategory.MODEL_CALL,
            phase = null,
            phaseSummaryJson = null,
            turnCount = 5,
            inputCount = null
        )

        composeRule.setContent {
            ChatWithChatTheme { MemoryActivityLogItem(log) }
        }

        composeRule.onAllNodesWithText("5 轮", substring = true, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("输入 5", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun activityLog(
        category: String = MemoryActivityCategory.TURN_BATCH_CONSOLIDATION,
        phase: String?,
        phaseSummaryJson: String?,
        turnCount: Int? = null,
        inputCount: Int? = 5
    ): MemoryActivityLog = MemoryActivityLog(
        logId = "activity-log-1",
        batchId = "batch-1",
        category = category,
        status = MemoryActivityStatus.SUCCEEDED,
        platformName = "Memory provider",
        modelName = "memory-model",
        attempt = 1,
        turnCount = turnCount,
        operationCount = 2,
        detail = null,
        startedAt = 10,
        completedAt = 14,
        updatedAt = 14,
        jobId = "job-1",
        jobType = "consolidate_turn_batch",
        phase = phase,
        triggerReason = "turn_batch_ready",
        platformUid = "platform-1",
        modelId = "memory-model",
        inputCount = inputCount,
        phaseSummaryJson = phaseSummaryJson
    )
}
