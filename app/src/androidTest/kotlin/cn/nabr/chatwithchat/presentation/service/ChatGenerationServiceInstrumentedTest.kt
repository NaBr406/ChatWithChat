package cn.nabr.chatwithchat.presentation.service

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.core.os.BundleCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.notification.HyperOsGenerationParams
import cn.nabr.chatwithchat.presentation.ui.chat.ChatTurnCoordinator
import cn.nabr.chatwithchat.presentation.ui.chat.ChatViewModel
import cn.nabr.chatwithchat.presentation.ui.main.MainActivity
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatGenerationServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var coordinator: ChatTurnCoordinator

    @Before
    fun setUp() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { coordinator = it.chatTurnCoordinator }
    }

    @After
    fun tearDown() {
        if (::coordinator.isInitialized) {
            coordinator.stopGenerationAttempts(coordinator.currentGenerationState().generatingAttemptIds)
            waitUntil { !isServiceRunning() }
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun backgroundGenerationKeepsServiceAndWakeLockUntilNotificationStop() {
        lateinit var turn: ChatTurnCoordinator.LiveTurn
        scenario.onActivity {
            turn = coordinator.start("draft:${UUID.randomUUID()}", request()) { live ->
                delay(1_000)
                live.messagesFlow.value = messages("background answer")
                awaitCancellation()
            }!!
        }
        waitUntil { isServiceRunning() && notification() != null }
        scenario.moveToState(Lifecycle.State.CREATED)
        shell("input keyevent KEYCODE_SLEEP")
        waitUntil { turn.messages.value.assistantMessages.first().first().content == "background answer" }
        assertTrue(isServiceRunning())
        assertTrue(shell("dumpsys power").contains("ChatWithChat:ChatGeneration"))
        notification()!!.notification.actions.single().actionIntent.send()
        waitUntil { coordinator.currentGenerationState().activeCount == 0 && !isServiceRunning() }
        waitUntil { notification() == null }
        assertTrue(turn.stopRequested)
        assertFalse(shell("dumpsys power").lineSequence().any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("ChatWithChat:ChatGeneration") })
    }

    @Test
    fun oldNotificationStopsOnlyItsAttemptsAndOtherChatKeepsRunning() {
        lateinit var first: ChatTurnCoordinator.LiveTurn
        lateinit var second: ChatTurnCoordinator.LiveTurn
        scenario.onActivity {
            first = coordinator.start("draft:${UUID.randomUUID()}", request()) { awaitCancellation() }!!
        }
        waitUntil { notification()?.notification?.actions?.isNotEmpty() == true }
        val oldStop = notification()!!.notification.actions.single().actionIntent
        scenario.onActivity {
            second = coordinator.start("draft:${UUID.randomUUID()}", request()) { awaitCancellation() }!!
        }
        waitUntil { coordinator.currentGenerationState().activeCount == 2 }
        oldStop.send()
        waitUntil { first.job!!.isCompleted }
        assertFalse(second.stopRequested)
        assertTrue(isServiceRunning())
        assertEquals(listOf(second.attemptId), coordinator.currentGenerationState().activeAttemptIds)
        coordinator.stopGenerationAttempts(listOf(second.attemptId))
        waitUntil { !isServiceRunning() && notification() == null }
    }

    @Test
    fun islandExtensionReusesStopActionAndFallsBackOnUnsupportedProtocol() {
        val previous = shell("settings get system notification_focus_protocol").trim()
        try {
            // This validates the Android notification payload, not Xiaomi SystemUI rendering.
            shell("settings put system notification_focus_protocol 3")
            lateinit var turn: ChatTurnCoordinator.LiveTurn
            scenario.onActivity {
                turn = coordinator.start("draft:${UUID.randomUUID()}", request()) { awaitCancellation() }!!
            }
            waitUntil { notification()?.notification?.extras?.containsKey(HyperOsGenerationParams.PARAM_KEY) == true }
            val island = notification()!!.notification
            val stop = BundleCompat.getParcelable(
                island.extras.getBundle(HyperOsGenerationParams.ACTIONS_KEY)!!,
                HyperOsGenerationParams.STOP_KEY,
                Notification.Action::class.java
            )!!
            assertEquals(island.actions.single().actionIntent, stop.actionIntent)
            assertTrue(island.extras.getBundle(HyperOsGenerationParams.PICS_KEY)!!.containsKey(HyperOsGenerationParams.ICON_KEY))
            stop.actionIntent.send()
            waitUntil { turn.job!!.isCompleted && !isServiceRunning() && notification() == null }

            shell("settings put system notification_focus_protocol 0")
            scenario.onActivity {
                coordinator.start("draft:${UUID.randomUUID()}", request()) { awaitCancellation() }
            }
            waitUntil { notification() != null }
            val ordinary = notification()!!.notification
            assertFalse(ordinary.extras.containsKey(HyperOsGenerationParams.PARAM_KEY))
            ordinary.actions.single().actionIntent.send()
            waitUntil { !isServiceRunning() && notification() == null }
        } finally {
            if (previous.isEmpty() || previous == "null") {
                shell("settings delete system notification_focus_protocol")
            } else {
                shell("settings put system notification_focus_protocol $previous")
            }
        }
    }

    @Test
    fun queuedStartsForAlreadyCompletedTurnsLeaveNoServiceOrNotification() {
        scenario.onActivity {
            repeat(8) {
                coordinator.start("draft:${UUID.randomUUID()}", request()) { }
            }
        }
        waitUntil { coordinator.currentGenerationState().activeCount == 0 }
        instrumentation.waitForIdleSync()
        waitUntil { !isServiceRunning() && notification() == null }
    }

    @Test
    fun androidDataSyncTimeoutStopsGenerationAndReleasesTheService() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 35)
        val previous = shell("device_config get activity_manager data_sync_fgs_timeout_duration").trim()
        try {
            shell("device_config put activity_manager data_sync_fgs_timeout_duration 3000")
            lateinit var turn: ChatTurnCoordinator.LiveTurn
            scenario.onActivity {
                turn = coordinator.start("draft:${UUID.randomUUID()}", request()) { awaitCancellation() }!!
            }
            waitUntil { isServiceRunning() }
            scenario.moveToState(Lifecycle.State.CREATED)
            waitUntil { turn.stopRequested && turn.job!!.isCompleted }
            waitUntil { !isServiceRunning() && notification() == null }
        } finally {
            if (previous.isEmpty() || previous == "null") {
                shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
            } else {
                shell("device_config put activity_manager data_sync_fgs_timeout_duration $previous")
            }
        }
    }

    // Invalid chat ID deliberately skips persistence: this lifecycle probe never changes user data
    // or calls a provider, and still exercises the production coordinator and foreground service.
    private fun request() = ChatTurnCoordinator.TurnRequest(
        chatRoom = MutableStateFlow(ChatRoomV2(id = -1, title = "Service test", enabledPlatform = emptyList())),
        chatPlatformModels = MutableStateFlow(emptyMap()),
        seedMessages = messages(""),
        seedLoadingStates = listOf(ChatViewModel.LoadingState.Loading),
        turnIndex = 0,
        platformIndex = 0
    )

    private fun messages(content: String) = ChatViewModel.GroupedMessages(
        userMessages = listOf(MessageV2(content = "service test", platformType = null)),
        assistantMessages = listOf(listOf(MessageV2(content = content, platformType = "test")))
    )

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100)
        .any { it.service.className == ChatGenerationService::class.java.name && it.foreground }

    private fun notification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == ChatGenerationService.NOTIFICATION_ID }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for generation service state" }
            SystemClock.sleep(50)
        }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
