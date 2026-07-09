package dev.chungjungsoo.gptmobile.presentation.ui.main

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.tool.ToolRegistry
import dev.chungjungsoo.gptmobile.presentation.common.LocalDynamicTheme
import dev.chungjungsoo.gptmobile.presentation.common.LocalNotificationPermissionRequester
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.NotificationPermissionRequester
import dev.chungjungsoo.gptmobile.presentation.common.Route
import dev.chungjungsoo.gptmobile.presentation.common.SetupNavGraph
import dev.chungjungsoo.gptmobile.presentation.common.ThemeSettingProvider
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var toolRegistry: ToolRegistry

    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var toolPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private val navigationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var pendingStartRoute: String? = null
    private var didRequestToolRuntimePermissionsOnLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        pendingStartRoute = intent.startRoute()
        installSplashScreen().apply {
            setKeepOnScreenCondition {
                !mainViewModel.isReady.value
            }
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        toolPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            // Permission denial is handled by ToolExecutor when a tool is invoked.
        }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Permission denial leaves maintenance work unaffected.
        }

        // Prevent keyboard from pushing the entire view up - composable handles insets via imePadding()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        requestToolRuntimePermissionsOnLaunch()

        setContent {
            val navController = rememberNavController()
            navController.checkForExistingSettings()
            navController.navigateFromNotificationIntents()

            ThemeSettingProvider {
                CompositionLocalProvider(
                    LocalNotificationPermissionRequester provides NotificationPermissionRequester(
                        ::requestPostNotificationsPermission
                    )
                ) {
                    GPTMobileTheme(
                        dynamicTheme = LocalDynamicTheme.current,
                        themeMode = LocalThemeMode.current
                    ) {
                        SetupNavGraph(navController)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.startRoute()?.let { route -> navigationRequests.tryEmit(route) }
    }

    private fun requestToolRuntimePermissionsOnLaunch() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!didRequestToolRuntimePermissionsOnLaunch) {
                    didRequestToolRuntimePermissionsOnLaunch = true
                    requestMissingToolRuntimePermissions()
                }
            }
        }
    }

    private fun requestMissingToolRuntimePermissions() {
        val missingPermissions = toolRegistry.requestedRuntimePermissions()
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
        if (missingPermissions.isNotEmpty()) {
            toolPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun NavHostController.checkForExistingSettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                mainViewModel.event.collect { event ->
                    when (event) {
                        MainViewModel.SplashEvent.OpenIntro -> {
                            navigate(Route.GET_STARTED) {
                                popUpTo(Route.CHAT_LIST) { inclusive = true }
                            }
                        }

                        MainViewModel.SplashEvent.OpenMigrate -> {
                            navigate(Route.MIGRATE_V2) {
                                popUpTo(Route.CHAT_LIST) { inclusive = true }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    @Composable
    private fun NavHostController.navigateFromNotificationIntents() {
        LaunchedEffect(this) {
            pendingStartRoute?.let { route ->
                pendingStartRoute = null
                navigateToStartRoute(route)
            }
            navigationRequests.collect { route -> navigateToStartRoute(route) }
        }
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
        const val EXTRA_START_ROUTE = "dev.chungjungsoo.gptmobile.extra.START_ROUTE"
    }
}
