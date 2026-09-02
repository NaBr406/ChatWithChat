package cn.nabr.chatwithchat.presentation.ui.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManagementScreenTest {

    @Test
    fun `first frame covers the viewport so streamed rows land off screen`() {
        listOf(560, 640, 800, 915, 1280).forEach { screenHeightDp ->
            val budget = modelRowRenderBudget(screenHeightDp)
            assertTrue(
                "budget of $budget rows must cover a ${screenHeightDp}dp viewport",
                budget * MODEL_ROW_HEIGHT_DP >= screenHeightDp
            )
        }
    }

    @Test
    fun `tiny viewports still get a workable batch`() {
        assertEquals(MODEL_ROW_MIN_BUDGET, modelRowRenderBudget(screenHeightDp = 0))
        assertEquals(MODEL_ROW_MIN_BUDGET, modelRowRenderBudget(screenHeightDp = 200))
    }
}
