package cn.nabr.chatwithchat.presentation.common

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.ui.chat.ChatScreen
import cn.nabr.chatwithchat.presentation.ui.chat.ChatShellScreen
import cn.nabr.chatwithchat.presentation.ui.chat.EmptyChatScreen
import cn.nabr.chatwithchat.presentation.ui.main.MainLaunchDestination
import cn.nabr.chatwithchat.presentation.ui.memory.MemoryScreen
import cn.nabr.chatwithchat.presentation.ui.migrate.MigrateScreen
import cn.nabr.chatwithchat.presentation.ui.setting.AboutScreen
import cn.nabr.chatwithchat.presentation.ui.setting.AddPlatformScreen
import cn.nabr.chatwithchat.presentation.ui.setting.LicenseScreen
import cn.nabr.chatwithchat.presentation.ui.setting.ModelManagementScreen
import cn.nabr.chatwithchat.presentation.ui.setting.PlatformSettingScreen
import cn.nabr.chatwithchat.presentation.ui.setting.SettingScreen
import cn.nabr.chatwithchat.presentation.ui.setting.SettingViewModelV2
import cn.nabr.chatwithchat.presentation.ui.setting.ToolSettingsScreen
import cn.nabr.chatwithchat.presentation.ui.setup.SetupCompleteScreen
import cn.nabr.chatwithchat.presentation.ui.setup.SetupPlatformListScreen
import cn.nabr.chatwithchat.presentation.ui.setup.SetupPlatformTypeScreen
import cn.nabr.chatwithchat.presentation.ui.setup.SetupPlatformWizardScreen
import cn.nabr.chatwithchat.presentation.ui.setup.SetupViewModelV2

@Composable
fun SetupNavGraph(
    navController: NavHostController,
    startDestination: MainLaunchDestination
) {
    NavHost(
        modifier = Modifier
            .fillMaxSize()
            .background(settingsMaterialColors().canvas),
        navController = navController,
        startDestination = startDestination.route
    ) {
        homeScreenNavigation(navController)
        migrationScreenNavigation(navController)
        setupNavigation(navController)
        settingNavigation(navController)
        chatScreenNavigation(navController)
    }
}

private val MainLaunchDestination.route: String
    get() = when (this) {
        MainLaunchDestination.Setup -> Route.SETUP_ROUTE
        MainLaunchDestination.Home -> Route.CHAT_LIST
        MainLaunchDestination.Migrate -> Route.MIGRATE_V2
    }

fun NavGraphBuilder.migrationScreenNavigation(navController: NavHostController) {
    composable(Route.MIGRATE_V2) {
        MigrateScreen {
            navController.navigate(Route.CHAT_LIST) {
                popUpTo(Route.MIGRATE_V2) { inclusive = true }
            }
        }
    }
}

fun NavGraphBuilder.setupNavigation(
    navController: NavHostController
) {
    navigation(startDestination = Route.SETUP_PLATFORM_LIST, route = Route.SETUP_ROUTE) {
        composable(route = Route.SETUP_PLATFORM_LIST) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            SetupPlatformListScreen(
                setupViewModel = setupViewModel,
                onAddPlatform = { navController.navigate(Route.SETUP_PLATFORM_TYPE) },
                onComplete = { navController.navigate(Route.SETUP_COMPLETE) }
            )
        }
        composable(route = Route.SETUP_PLATFORM_TYPE) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            SetupPlatformTypeScreen(
                setupViewModel = setupViewModel,
                onPlatformTypeSelected = { navController.navigate(Route.SETUP_PLATFORM_WIZARD) },
                onBackAction = { navController.navigateUp() }
            )
        }
        composable(route = Route.SETUP_PLATFORM_WIZARD) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            SetupPlatformWizardScreen(
                setupViewModel = setupViewModel,
                onComplete = {
                    navController.navigate(Route.CHAT_LIST) {
                        popUpTo(Route.SETUP_ROUTE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBackAction = { navController.navigateUp() }
            )
        }
        composable(route = Route.SETUP_COMPLETE) {
            SetupCompleteScreen(
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(Route.SETUP_ROUTE) { inclusive = true }
                    }
                },
                onBackAction = { navController.navigateUp() }
            )
        }
    }
}

fun NavGraphBuilder.homeScreenNavigation(navController: NavHostController) {
    composable(Route.CHAT_LIST) {
        ChatShellScreen(
            settingOnClick = { navController.navigate(Route.SETTING_ROUTE) { launchSingleTop = true } },
            onAboutClick = { navController.navigate(Route.ABOUT_PAGE) { launchSingleTop = true } },
            onExistingChatClick = { chatRoom ->
                navController.navigateToChatRoom(chatRoom.id, chatRoom.enabledPlatform)
            },
            navigateToNewChat = { enabledPlatforms, initialQuestion, initialModel, initialAttachmentPaths, initialRequestId ->
                navController.navigateToChatRoom(0, enabledPlatforms, initialQuestion, initialModel, initialAttachmentPaths, initialRequestId)
            }
        ) { openDrawer, homeViewModel, startNewChat ->
            EmptyChatScreen(
                homeViewModel = homeViewModel,
                onOpenDrawer = openDrawer,
                onStartChat = { prompt, attachmentPaths -> startNewChat(prompt, attachmentPaths, true) },
                onAddProvider = { navController.navigate(Route.ADD_PLATFORM) }
            )
        }
    }
}

private fun NavHostController.navigateToChatRoom(
    chatRoomId: Int,
    enabledPlatforms: List<String>,
    initialQuestion: String? = null,
    initialModel: String? = null,
    initialAttachmentPaths: List<String> = emptyList(),
    initialRequestId: Int = 0
) {
    val enabledPlatformString = enabledPlatforms.joinToString(",")
    val encodedInitialQuestion = Uri.encode(initialQuestion.orEmpty())
    val encodedInitialModel = Uri.encode(initialModel.orEmpty())
    val encodedInitialAttachments = Uri.encode(initialAttachmentPaths.joinToString("\n"))
    navigate(
        Route.CHAT_ROOM
            .replace(oldValue = "{chatRoomId}", newValue = "$chatRoomId")
            .replace(oldValue = "{enabledPlatforms}", newValue = enabledPlatformString)
            .replace(oldValue = "{initialQuestion}", newValue = encodedInitialQuestion)
            .replace(oldValue = "{initialModel}", newValue = encodedInitialModel)
            .replace(oldValue = "{initialAttachments}", newValue = encodedInitialAttachments)
            .replace(oldValue = "{initialRequestId}", newValue = "$initialRequestId")
    )
}

fun NavGraphBuilder.chatScreenNavigation(navController: NavHostController) {
    composable(
        Route.CHAT_ROOM,
        arguments = listOf(
            navArgument("chatRoomId") { type = NavType.IntType },
            navArgument("enabledPlatforms") { defaultValue = "" },
            navArgument("initialQuestion") { defaultValue = "" },
            navArgument("initialModel") { defaultValue = "" },
            navArgument("initialAttachments") { defaultValue = "" },
            navArgument("initialRequestId") {
                type = NavType.IntType
                defaultValue = 0
            }
        )
    ) {
        ChatShellScreen(
            settingOnClick = { navController.navigate(Route.SETTING_ROUTE) { launchSingleTop = true } },
            onAboutClick = { navController.navigate(Route.ABOUT_PAGE) { launchSingleTop = true } },
            onExistingChatClick = { chatRoom ->
                navController.navigateToChatRoom(chatRoom.id, chatRoom.enabledPlatform)
            },
            navigateToNewChat = { enabledPlatforms, initialQuestion, initialModel, initialAttachmentPaths, initialRequestId ->
                navController.navigateToChatRoom(0, enabledPlatforms, initialQuestion, initialModel, initialAttachmentPaths, initialRequestId)
            }
        ) { openDrawer, _, _ ->
            ChatScreen(
                onBackAction = openDrawer,
                navigationIcon = Icons.Rounded.Menu,
                navigationIconContentDescription = stringResource(R.string.open_chat_history)
            )
        }
    }
}

fun NavGraphBuilder.settingNavigation(navController: NavHostController) {
    navigation(startDestination = Route.SETTINGS, route = Route.SETTING_ROUTE) {
        composable(Route.SETTINGS) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            SettingScreen(
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() },
                onNavigateToAddPlatform = { navController.navigate(Route.ADD_PLATFORM) },
                onNavigateToPlatformSetting = { platformUid ->
                    navController.navigate(
                        Route.PLATFORM_SETTINGS.replace("{platformUid}", platformUid)
                    )
                },
                onNavigateToModelManagement = { navController.navigate(Route.MODEL_MANAGEMENT) },
                onNavigateToToolSettings = { navController.navigate(Route.TOOL_SETTINGS) },
                onNavigateToMemory = { navController.navigate(Route.MEMORY) },
                onNavigateToAboutPage = { navController.navigate(Route.ABOUT_PAGE) }
            )
        }
        composable(Route.TOOL_SETTINGS) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            ToolSettingsScreen(
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(Route.MODEL_MANAGEMENT) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            ModelManagementScreen(
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(Route.ADD_PLATFORM) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            AddPlatformScreen(
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(
            Route.PLATFORM_SETTINGS,
            arguments = listOf(navArgument("platformUid") { type = NavType.StringType })
        ) {
            PlatformSettingScreen(
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(Route.ABOUT_PAGE) {
            AboutScreen(
                onNavigationClick = { navController.navigateUp() },
                onNavigationToLicense = { navController.navigate(Route.LICENSE) }
            )
        }
        composable(Route.MEMORY) {
            MemoryScreen(onNavigationClick = { navController.navigateUp() })
        }
        composable(Route.LICENSE) {
            LicenseScreen(onNavigationClick = { navController.navigateUp() })
        }
    }
}
