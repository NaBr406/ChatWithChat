package cn.nabr.chatwithchat.presentation.ui.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFlowPolicyTest {
    @Test
    fun shouldOpenHome_successWithModel_returnsTrue() {
        assertTrue(SaveStatus.Success(modelCount = 1).shouldOpenHome())
    }

    @Test
    fun shouldOpenHome_successWithoutModel_returnsFalse() {
        assertFalse(SaveStatus.Success(modelCount = 0).shouldOpenHome())
    }

    @Test
    fun shouldOpenHome_refreshFailure_returnsFalse() {
        assertFalse(SaveStatus.Error(message = "failed", platformSaved = true).shouldOpenHome())
    }
}
