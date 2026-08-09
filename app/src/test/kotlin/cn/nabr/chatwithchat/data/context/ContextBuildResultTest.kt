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
        assertFalse(result(ContextStrategy.AUTO_COMPACT_BALANCED, BUDGET.rawWarningThreshold).isNearBudgetLimit)
        assertFalse(result(ContextStrategy.AUTO_COMPACT_BALANCED, BUDGET.effectiveInputBudget).isNearBudgetLimit)
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
    fun `a background fold is enqueued only when there is a batch worth folding`() {
        val enough = BUDGET.backgroundFoldMinBatchTokens

        assertFalse(
            "nothing to fold",
            result(pendingFoldTokens = enough, pendingFoldTurns = emptyList()).shouldEnqueueBackgroundFold
        )
        assertFalse(
            "batch too small to pay for a provider call",
            result(
                pendingFoldTokens = enough - 1,
                pendingFoldTurns = listOf(turn(1))
            ).shouldEnqueueBackgroundFold
        )
        assertTrue(
            result(
                pendingFoldTokens = enough,
                pendingFoldTurns = listOf(turn(1))
            ).shouldEnqueueBackgroundFold
        )
    }

    @Test
    fun `the fold decision ignores the assembled request size`() {
        // The defect this guards: the predicate used to compare estimatedTokens against
        // preFoldTrigger. Every part of estimatedTokens is already clamped by the budget under the
        // AUTO_COMPACT family, so it sat permanently below the line and no fold was ever enqueued —
        // which in turn meant the checkpoint chain was never written and the extractive fallback
        // became the only path. A request at the very top of its budget with nothing stranded behind
        // it must still not fold, and a small request with a large backlog must.
        assertFalse(
            "a full request is not evidence of unfolded history",
            result(
                estimatedTokens = BUDGET.effectiveInputBudget,
                pendingFoldTokens = 0,
                pendingFoldTurns = emptyList()
            ).shouldEnqueueBackgroundFold
        )
        assertTrue(
            "a bounded request can still have a long backlog behind it",
            result(
                estimatedTokens = 0,
                pendingFoldTokens = BUDGET.backgroundFoldMinBatchTokens,
                pendingFoldTurns = listOf(turn(1))
            ).shouldEnqueueBackgroundFold
        )
    }

    @Test
    fun `a realistic auto compact request would never have folded under the old rule`() {
        // Concrete form of the same bug, so the regression is legible without re-deriving the ratios:
        // the largest request AUTO_COMPACT can assemble is the raw tail plus a checkpoint, and even
        // that stays far below preFoldTrigger.
        val largestPossibleRequest = BUDGET.recentRawTail + BUDGET.summaryTarget

        assertTrue(
            "if this ever stops holding, the old predicate was not the bug it is documented as",
            largestPossibleRequest < BUDGET.preFoldTrigger
        )
    }

    @Test
    fun `raw preferred waits for the conversation itself to approach the line`() {
        // The user picked RAW_PREFERRED to avoid paying for compaction, so a fold whose checkpoint may
        // never be needed is not free — it is the cost they opted out of. Under this strategy the raw
        // tail is budgeted at the whole effectiveInputBudget, so estimatedTokens really does track the
        // history and can carry the decision, which it cannot do under AUTO_COMPACT.
        val bigBatch = BUDGET.backgroundFoldMinBatchTokens

        assertFalse(
            "still far from capacity, so folding would be speculative",
            result(
                strategy = ContextStrategy.RAW_PREFERRED,
                estimatedTokens = BUDGET.preFoldTrigger - 1,
                pendingFoldTokens = bigBatch,
                pendingFoldTurns = listOf(turn(1))
            ).shouldEnqueueBackgroundFold
        )
        assertTrue(
            "at the line the checkpoint is about to be needed",
            result(
                strategy = ContextStrategy.RAW_PREFERRED,
                estimatedTokens = BUDGET.preFoldTrigger,
                pendingFoldTokens = bigBatch,
                pendingFoldTurns = listOf(turn(1))
            ).shouldEnqueueBackgroundFold
        )
    }

    @Test
    fun `raw preferred still requires a batch worth folding`() {
        // The size floor applies to every strategy: being near capacity is no reason to pay for a
        // provider call that cannot shrink anything.
        assertFalse(
            result(
                strategy = ContextStrategy.RAW_PREFERRED,
                estimatedTokens = BUDGET.effectiveInputBudget,
                pendingFoldTokens = BUDGET.backgroundFoldMinBatchTokens - 1,
                pendingFoldTurns = listOf(turn(1))
            ).shouldEnqueueBackgroundFold
        )
    }

    @Test
    fun `every auto compact tier folds on the batch alone`() {
        // The bug was strategy-wide, so the fix has to be too: no tier may reintroduce a dependency on
        // the assembled request size, since every tier clamps that size by construction.
        ContextStrategy.entries
            .filter { it.isAutoCompact }
            .forEach { strategy ->
                assertTrue(
                    strategy.name,
                    result(
                        strategy = strategy,
                        estimatedTokens = 0,
                        pendingFoldTokens = BUDGET.backgroundFoldMinBatchTokens,
                        pendingFoldTurns = listOf(turn(1))
                    ).shouldEnqueueBackgroundFold
                )
            }
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
        strategy: ContextStrategy = ContextStrategy.AUTO_COMPACT_BALANCED,
        estimatedTokens: Int = 0,
        compactionState: ContextCompactionState = ContextCompactionState.NONE,
        pendingFoldTurns: List<ConversationTurn> = emptyList(),
        pendingFoldTokens: Int = 0,
        checkpointBody: String? = null,
        deterministicSummary: String? = null
    ) = ContextBuildResult(
        strategy = strategy,
        range = ContextRange.fullHistory(chatRoomId = 1, platformUid = "platform"),
        budget = BUDGET,
        checkpointBody = checkpointBody,
        deterministicSummary = deterministicSummary,
        pendingFoldTurns = pendingFoldTurns,
        pendingFoldTokens = pendingFoldTokens,
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
