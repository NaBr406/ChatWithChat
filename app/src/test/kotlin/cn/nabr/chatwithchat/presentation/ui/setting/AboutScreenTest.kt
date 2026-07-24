package cn.nabr.chatwithchat.presentation.ui.setting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutScreenTest {

    @Test
    fun `prompt trace unlocks on seventh version tap`() {
        repeat(6) { currentTapCount ->
            assertFalse(isPromptTraceUnlockTap(currentTapCount))
        }

        assertTrue(isPromptTraceUnlockTap(6))
    }
}
