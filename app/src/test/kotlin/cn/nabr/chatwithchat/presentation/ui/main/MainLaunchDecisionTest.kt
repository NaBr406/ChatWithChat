package cn.nabr.chatwithchat.presentation.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MainLaunchDecisionTest {
    @Test
    fun resolveSplashEvent_freshInstall_opensSetupWithoutIntro() {
        val event = resolveSplashEvent(
            hasConfiguredLegacyPlatform = false,
            hasV2Platforms = false
        )

        assertEquals(MainViewModel.SplashEvent.OpenSetup, event)
    }

    @Test
    fun resolveSplashEvent_existingV2Platform_opensHome() {
        val event = resolveSplashEvent(
            hasConfiguredLegacyPlatform = false,
            hasV2Platforms = true
        )

        assertEquals(MainViewModel.SplashEvent.OpenHome, event)
    }

    @Test
    fun resolveSplashEvent_legacyPlatformWithoutV2_opensMigration() {
        val event = resolveSplashEvent(
            hasConfiguredLegacyPlatform = true,
            hasV2Platforms = false
        )

        assertEquals(MainViewModel.SplashEvent.OpenMigrate, event)
    }
}
