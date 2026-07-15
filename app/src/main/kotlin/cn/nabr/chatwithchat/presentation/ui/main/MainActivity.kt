package cn.nabr.chatwithchat.presentation.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import cn.nabr.chatwithchat.presentation.common.LocalDynamicTheme
import cn.nabr.chatwithchat.presentation.common.LocalNotificationPermissionRequester
import cn.nabr.chatwithchat.presentation.common.LocalThemeMode
import cn.nabr.chatwithchat.presentation.common.LocalToolPermissionRequester
import cn.nabr.chatwithchat.presentation.common.NotificationPermissionRequester
import cn.nabr.chatwithchat.presentation.common.Route
import cn.nabr.chatwithchat.presentation.common.SetupNavGraph
import cn.nabr.chatwithchat.presentation.common.ThemeSettingProvider
import cn.nabr.chatwithchat.presentation.common.ToolPermissionRequester
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var toolRegistry: ToolRegistry

    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var toolPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private val navigationRequests = createNavigationRequestChannel()
    private var pendingToolPermissionRequest: PendingToolPermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.startRoute()?.let(::enqueueNavigationRequest)
        installSplashScreen().apply {
            setKeepOnScreenCondition {
                mainViewModel.launchState.value is MainLaunchState.Loading
            }
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        toolPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            val pendingRequest = pendingToolPermissionRequest
            pendingToolPermissionRequest = null
            pendingRequest?.onResult?.invoke(areToolPermissionsGranted(pendingRequest.toolName))
        }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Permission denial leaves maintenance work unaffected.
        }

        // Prevent keyboard from pushing the entire view up - composable handles insets via imePadding()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setContent {
            val launchState by mainViewModel.launchState.collectAsStateWithLifecycle()

            ThemeSettingProvider {
                CompositionLocalProvider(
                    LocalNotificationPermissionRequester provides NotificationPermissionRequester(
                        ::requestPostNotificationsPermission
                    ),
                    LocalToolPermissionRequester provides ToolPermissionRequester(::requestToolRuntimePermissions)
                ) {
                    ChatWithChatTheme(
                        dynamicTheme = LocalDynamicTheme.current,
                        themeMode = LocalThemeMode.current
                    ) {
                        when (val state = launchState) {
                            MainLaunchState.Loading -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(settingsMaterialColors().canvas)
                                )
                            }

                            is MainLaunchState.Resolved -> {
                                key(launchNavigationStateKey(state.destination)) {
                                    val navController = rememberNavController()
                                    SetupNavGraph(
                                        navController = navController,
                                        startDestination = state.destination
                                    )
                                    navController.navigateFromNotificationIntents()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.startRoute()?.let(::enqueueNavigationRequest)
    }

    private fun requestToolRuntimePermissions(toolName: String, onResult: (Boolean) -> Unit) {
        if (pendingToolPermissionRequest != null) {
            onResult(false)
            return
        }
        val requirements = toolRegistry.permissionRequirementsFor(toolName)
        if (requirements.isEmpty() || areToolPermissionsGranted(toolName)) {
            onResult(true)
            return
        }

        val missingPermissions = requirements
            .flatMap { requirement -> requirement.requestedPermissions() }
            .distinct()
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
        if (missingPermissions.isNotEmpty()) {
            pendingToolPermissionRequest = PendingToolPermissionRequest(toolName, onResult)
            toolPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            onResult(areToolPermissionsGranted(toolName))
        }
    }

    private fun areToolPermissionsGranted(toolName: String): Boolean =
        toolRegistry.permissionRequirementsFor(toolName).all { requirement ->
            requirement.isSatisfied { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }
        }

    private fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Composable
    private fun NavHostController.navigateFromNotificationIntents() {
        LaunchedEffect(this) {
            navigationRequests.receiveAsFlow().collect { route -> navigateToStartRoute(route) }
        }
    }

    private fun enqueueNavigationRequest(route: String) {
        navigationRequests.trySend(route).getOrThrow()
    }

    private fun NavHostController.navigateToStartRoute(route: String) {
        if (route != Route.MEMORY) return
        navigate(Route.MEMORY) {
            launchSingleTop = true
        }
    }

    private fun Intent?.startRoute(): String? =
        this?.getStringExtra(EXTRA_START_ROUTE)?.takeIf { route -> route == Route.MEMORY }

    companion object {
        const val EXTRA_START_ROUTE = "cn.nabr.chatwithchat.extra.START_ROUTE"
    }

    private data class PendingToolPermissionRequest(
        val toolName: String,
        val onResult: (Boolean) -> Unit
    )
}

internal fun launchNavigationStateKey(destination: MainLaunchDestination): String =
    "launch-${destination.name}"

internal fun createNavigationRequestChannel(): Channel<String> = Channel(Channel.UNLIMITED)
