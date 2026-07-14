package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RoundNavigatorPolicyTest {
    @Test
    fun completedRoundCount_partialRound_isNotCompleted() {
        val items = listOf(
            roundItem(status = RoundStatus.Completed),
            roundItem(status = RoundStatus.Partial),
            roundItem(status = RoundStatus.Failed),
            roundItem(status = RoundStatus.Generating)
        )

        assertEquals(1, items.completedRoundCount())
    }

    private fun roundItem(status: RoundStatus) = RoundNavigationItem(
        turnIndex = 0,
        displayNumber = 1,
        questionPreview = "question",
        status = status,
        hasSuccessfulAnswer = status == RoundStatus.Completed || status == RoundStatus.Partial,
        totalTokens = 0,
        isEstimated = false,
        platformUsages = emptyList()
    )
}
