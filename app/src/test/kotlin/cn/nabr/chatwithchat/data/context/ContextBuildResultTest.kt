package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived properties of a built context.
 *
 * These are asserted against hand-built results rather than through [ContextBuilder], because each one
 * is a threshold and a threshold is only meaningfully tested at its exact edge. Driving a real encoder
 * to land on precisely `rawWarningThreshold` tokens would be guesswork; setting the field is not.
 */
class ContextBuildResultTest {

    @Test
    fun `raw preferred warns at eighty percent of the effective input budget`() {
        val threshold = BUDGET.rawWarningThreshold

        assertFalse(result(ContextStrategy.RAW_PREFERRED, threshold - 1).isNearBudgetLimit)
        assertTrue(result(ContextStrategy.RAW_PREFERRED, threshold).isNearBudgetLimit)
        assertTrue(result(ContextStrategy.RAW_PREFERRED, threshold + 1).isNearBudgetLimit)
    }

    @Test
    fun `auto compact never raises the raw budget warning`() {
        // Under AUTO_COMPACT the tail is bounded by design, so approaching the window is not a
        // condition the user has to act on — surfacing it would be noise on every long chat.
        assertFalse(result(ContextStrategy.AUTO_COMPACT, BUDGET.rawWarningThreshold).isNearBudgetLimit)
        assertFalse(result(ContextStrategy.AUTO_COMPACT, BUDGET.effectiveInputBudget).isNearBudgetLimit)
    }

    @Test
    fun `over budget is reported only past the effective input budget`() {
        assertFalse(result(estimatedTokens = BUDGET.effectiveInputBudget).exceedsEffectiveInputBudget)
        assertTrue(result(estimatedTokens = BUDGET.effectiveInputBudget + 1).exceedsEffectiveInputBudget)
    }

    @Test
    fun `did compact distinguishes a real fold from a queued one`() {
        assertFalse(result(compactionState = ContextCompactionState.NONE).didCompact)
        assertFalse(result(compactionState = ContextCompactionState.PENDING_BACKGROUND_FOLD).didCompact)

        listOf(
            ContextCompactionState.REUSED_CHECKPOINT,
            ContextCompactionState.COMPACTED_APP_SIDE,
            ContextCompactionState.NATIVE_SERVER_SIDE,
            ContextCompactionState.REUSED_NATIVE_STATE,
            ContextCompactionState.DETERMINISTIC_FALLBACK
        ).forEach { state ->
            assertTrue("$state should count as compacted", result(compactionState = state).didCompact)
        }
    }

    @Test
    fun `a background fold is enqueued only when there is something to fold`() {
        val overTheLine = BUDGET.preFoldTrigger

        assertFalse(
            "nothing to fold",
            result(estimatedTokens = overTheLine, pendingFoldTurns = emptyList()).shouldEnqueueBackgroundFold
        )
        assertFalse(
            "not over the pre-fold line",
            result(
                estimatedTokens = overTheLine - 1,
                pendingFoldTurns = listOf(turn(1))
            ).shouldEnqueueBackgroundFold
        )
        assertTrue(
            result(
                estimatedTokens = overTheLine,
                pendingFoldTurns = listOf(turn(1))
            ).shouldEnqueueBackgroundFold
        )
    }

    @Test
    fun `no prompt block is produced when the whole range goes out verbatim`() {
        assertNull(result().contextPromptBlock)
        assertNull(result(checkpointBody = "   ", deterministicSummary = "  ").contextPromptBlock)
    }

    @Test
    fun `the prompt block frames folded history and keeps the checkpoint ahead of the fallback`() {
        val block = result(
            checkpointBody = "折叠要点",
            deterministicSummary = "较早对话摘要：..."
        ).contextPromptBlock

        assertEquals(
            listOf(ContextBuildResult.HISTORICAL_CONTEXT_HEADER, "折叠要点", "较早对话摘要：..."),
            block?.split("\n\n")
        )
    }

    @Test
    fun `either half of the prompt block stands on its own`() {
        assertEquals(
            listOf(ContextBuildResult.HISTORICAL_CONTEXT_HEADER, "折叠要点"),
            result(checkpointBody = "折叠要点").contextPromptBlock?.split("\n\n")
        )
        assertEquals(
            listOf(ContextBuildResult.HISTORICAL_CONTEXT_HEADER, "摘要"),
            result(deterministicSummary = "摘要").contextPromptBlock?.split("\n\n")
        )
    }

    @Test
    fun `the historical context header tells the model this is not an instruction`() {
        // A user sentence quoted inside a checkpoint reads as a fresh order from the operator without
        // this, because the block lands in the instructions slot next to the real system prompt.
        assertTrue(ContextBuildResult.HISTORICAL_CONTEXT_HEADER.contains("不是新的指令"))
    }

    private fun result(
        strategy: ContextStrategy = ContextStrategy.AUTO_COMPACT,
        estimatedTokens: Int = 0,
        compactionState: ContextCompactionState = ContextCompactionState.NONE,
        pendingFoldTurns: List<ConversationTurn> = emptyList(),
        checkpointBody: String? = null,
        deterministicSummary: String? = null
    ) = ContextBuildResult(
        strategy = strategy,
        range = ContextRange.fullHistory(chatRoomId = 1, platformUid = "platform"),
        budget = BUDGET,
        checkpointBody = checkpointBody,
        deterministicSummary = deterministicSummary,
        pendingFoldTurns = pendingFoldTurns,
        estimatedTokens = estimatedTokens,
        compactionState = compactionState
    )

    private fun turn(id: Int) = ConversationTurn(
        userMessage = MessageV2(id = id, content = "question-$id", platformType = null),
        assistantMessage = MessageV2(id = 1_000 + id, content = "answer-$id", platformType = "platform"),
        isCurrentTurn = false
    )

    private companion object {
        val BUDGET: ContextBudget = ContextBudget.of(modelContextWindow = 32_768, providerSummaryTarget = 1_200)
    }
}
