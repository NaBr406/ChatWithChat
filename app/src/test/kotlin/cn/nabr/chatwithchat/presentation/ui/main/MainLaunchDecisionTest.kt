package cn.nabr.chatwithchat.presentation.ui.main

import cn.nabr.chatwithchat.presentation.common.Route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainLaunchDecisionTest {
    @Test
    fun resolveLaunchDestination_freshInstall_startsInSetup() {
        val destination = resolveLaunchDestination(
            hasConfiguredLegacyPlatform = false,
            hasV2Platforms = false
        )

        assertEquals(MainLaunchDestination.Setup, destination)
    }

    @Test
    fun resolveLaunchDestination_existingV2Platform_startsInHome() {
        val destination = resolveLaunchDestination(
            hasConfiguredLegacyPlatform = false,
            hasV2Platforms = true
        )

        assertEquals(MainLaunchDestination.Home, destination)
    }

    @Test
    fun resolveLaunchDestination_legacyPlatformWithoutV2_startsInMigration() {
        val destination = resolveLaunchDestination(
            hasConfiguredLegacyPlatform = true,
            hasV2Platforms = false
        )

        assertEquals(MainLaunchDestination.Migrate, destination)
    }

    @Test
    fun launchState_beforeRepositoryDecision_isLoading() {
        assertEquals(MainLaunchState.Loading, initialMainLaunchState())
    }

    @Test
    fun resolvedDestination_changesNavigationRestoreIdentity() {
        assertNotEquals(
            launchNavigationStateKey(MainLaunchDestination.Setup),
            launchNavigationStateKey(MainLaunchDestination.Home)
        )
    }

    @Test
    fun navigationRequest_beforeResolvedCollector_isQueuedAndConsumedOnce() = runBlocking {
        val requests = createNavigationRequestChannel()

        assertTrue(requests.trySend(Route.MEMORY).isSuccess)
        assertEquals(Route.MEMORY, requests.receiveAsFlow().first())
        assertTrue(requests.tryReceive().isFailure)
    }
}
