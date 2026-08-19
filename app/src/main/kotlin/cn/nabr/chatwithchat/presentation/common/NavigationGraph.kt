package cn.nabr.chatwithchat.presentation.common

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import cn.nabr.chatwithchat.data.textskill.PendingTextSkillSelection
import cn.nabr.chatwithchat.presentation.ui.chat.AttachmentPreviewScreen
import cn.nabr.chatwithchat.presentation.ui.chat.ChatScreen
import cn.nabr.chatwithchat.presentation.ui.chat.ChatShellScreen
import cn.nabr.chatwithchat.presentation.ui.chat.ChatViewModel
import cn.nabr.chatwithchat.presentation.ui.chat.EmptyChatScreen
import cn.nabr.chatwithchat.presentation.ui.chat.encodePendingTextSkillSelections
import cn.nabr.chatwithchat.presentation.ui.debug.PromptTraceScreen
import cn.nabr.chatwithchat.presentation.ui.main.MainLaunchDestination
import cn.nabr.chatwithchat.presentation.ui.memory.MemoryScreen
import cn.nabr.chatwithchat.presentation.ui.migrate.MigrateScreen
import cn.nabr.chatwithchat.presentation.ui.setting.AboutScreen
import cn.nabr.chatwithchat.presentation.ui.setting.AddPlatformScreen
import cn.nabr.chatwithchat.presentation.ui.setting.ContextStrategyScreen
import cn.nabr.chatwithchat.presentation.ui.setting.LicenseScreen
import cn.nabr.chatwithchat.presentation.ui.setting.ModelManagementScreen
import cn.nabr.chatwithchat.presentation.ui.setting.PlatformSettingScreen
import cn.nabr.chatwithchat.presentation.ui.setting.SettingScreen
import cn.nabr.chatwithchat.presentation.ui.setting.SettingViewModelV2
import cn.nabr.chatwithchat.presentation.ui.setting.SkillsScreen
import cn.nabr.chatwithchat.presentation.ui.setting.StickerLibraryScreen
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
    val attachmentPreviewLauncher = remember(navController) {
        AttachmentPreviewLauncher { filePath -> navController.navigateToAttachmentPreview(filePath) }
    }
    CompositionLocalProvider(LocalAttachmentPreviewLauncher provides attachmentPreviewLauncher) {
        NavHost(
            modifier = Modifier
                .fillMaxSize()
                .background(settingsMaterialColors().canvas),
            navController = navController,
            startDestination = startDestination.route,
            enterTransition = NavEnterTransition,
            exitTransition = NavExitTransition,
            popEnterTransition = NavPopEnterTransition,
            popExitTransition = NavPopExitTransition
        ) {
            homeScreenNavigation(navController)
            migrationScreenNavigation(navController)
            setupNavigation(navController)
            settingNavigation(navController)
            chatScreenNavigation(navController)
            attachmentPreviewNavigation(navController)
        }
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
            navigateToNewChat = { enabledPlatforms, initialQuestion, initialModel, initialAttachmentPaths, initialRequestId, initialTextSkills ->
                navController.navigateToChatRoom(
                    chatRoomId = 0,
                    enabledPlatforms = enabledPlatforms,
                    initialQuestion = initialQuestion,
                    initialModel = initialModel,
                    initialAttachmentPaths = initialAttachmentPaths,
                    initialRequestId = initialRequestId,
                    initialTextSkills = initialTextSkills
                )
            }
        ) { openDrawer, homeViewModel, startNewChat ->
            EmptyChatScreen(
                homeViewModel = homeViewModel,
                onOpenDrawer = openDrawer,
                onStartChat = { prompt, attachmentPaths, textSkills ->
                    startNewChat(prompt, attachmentPaths, textSkills, true)
                },
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
    initialRequestId: Int = 0,
    initialTextSkills: List<PendingTextSkillSelection> = emptyList()
) {
    val enabledPlatformString = enabledPlatforms.joinToString(",")
    val encodedInitialQuestion = Uri.encode(initialQuestion.orEmpty())
    val encodedInitialModel = Uri.encode(initialModel.orEmpty())
    val encodedInitialAttachments = Uri.encode(initialAttachmentPaths.joinToString("\n"))
    val encodedInitialTextSkills = Uri.encode(encodePendingTextSkillSelections(initialTextSkills))
    navigate(
        Route.CHAT_ROOM
            .replace(oldValue = "{chatRoomId}", newValue = "$chatRoomId")
            .replace(oldValue = "{enabledPlatforms}", newValue = enabledPlatformString)
            .replace(oldValue = "{initialQuestion}", newValue = encodedInitialQuestion)
            .replace(oldValue = "{initialModel}", newValue = encodedInitialModel)
            .replace(oldValue = "{initialAttachments}", newValue = encodedInitialAttachments)
            .replace(oldValue = "{initialRequestId}", newValue = "$initialRequestId")
            .replace(oldValue = "{initialTextSkills}", newValue = encodedInitialTextSkills)
    ) {
        // 会话之间是平级的。抽屉在首页和会话页里都开得出来，每次点选又都带着不同的参数，不加约束的话
        // 连开 N 个会话就在返回栈上堆 N 层，返回键得把逛过的历史会话一路倒退完才出得去应用。
        //
        // 弹到 CHAT_LIST 但不含它自身，栈里于是恒为「首页 + 当前会话」两层：从会话里返回总是回到首页，
        // 再返回才退出。不用 popUpTo(CHAT_ROOM) { inclusive = true } 是因为那条要靠带占位符的路由模板
        // 去匹配，而首页这个锚点没有参数，怎么进来的都对得上。
        //
        // 代价：切会话会连着上一个会话的 ViewModel 一起销毁，它那边没跑完的流式回复就断在半路。这跟在
        // 会话里按返回键的行为是一致的（也断），必须留住的副作用——记忆归档、标题生成——本来就跑在
        // applicationScope 上，不受这里影响。
        popUpTo(Route.CHAT_LIST)
    }
}

private fun NavHostController.navigateToAttachmentPreview(filePath: String) {
    // 路径带 `/`，只能进 query 参数；读取端跟本文件其他参数一样再 Uri.decode 一次。
    navigate(Route.ATTACHMENT_PREVIEW.replace(oldValue = "{path}", newValue = Uri.encode(filePath))) {
        launchSingleTop = true
    }
}

fun NavGraphBuilder.attachmentPreviewNavigation(navController: NavHostController) {
    composable(
        Route.ATTACHMENT_PREVIEW,
        arguments = listOf(navArgument("path") { defaultValue = "" })
    ) { entry ->
        AttachmentPreviewScreen(
            filePath = Uri.decode(entry.arguments?.getString("path").orEmpty()),
            onNavigationClick = { navController.navigateUp() }
        )
    }
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
            },
            navArgument("initialTextSkills") { defaultValue = "" }
        )
    ) {
        // 和 ChatScreen 默认参数取到的是同一个实例——两边的 hiltViewModel() 都落在这个 NavBackStackEntry
        // 上。提到这里是为了在下面比对当前会话 id，顺手显式传下去，免得看着像各建了一个。
        val chatViewModel: ChatViewModel = hiltViewModel()
        ChatShellScreen(
            settingOnClick = { navController.navigate(Route.SETTING_ROUTE) { launchSingleTop = true } },
            onAboutClick = { navController.navigate(Route.ABOUT_PAGE) { launchSingleTop = true } },
            onExistingChatClick = { chatRoom ->
                // 点到的就是正在看的这个会话时什么都不做，抽屉那边已经在关了。否则会走一遍 popUpTo：当前
                // entry 连 ViewModel 一起销毁，再推一个参数完全相同的新 entry，消息重新查一遍、滚动位置
                // 归零，用户看到的是原地闪一下。
                //
                // 比的是 ViewModel 里的实时 id 而不是路由参数：从空白页新建的会话路由参数恒为 0，落库拿到
                // 真 id 之后只有 _chatRoom 跟得上，拿 0 去比会漏掉「刚建完就在抽屉里点它」这一档。
                if (chatRoom.id != chatViewModel.chatRoom.value.id) {
                    navController.navigateToChatRoom(chatRoom.id, chatRoom.enabledPlatform)
                }
            },
            navigateToNewChat = { enabledPlatforms, initialQuestion, initialModel, initialAttachmentPaths, initialRequestId, initialTextSkills ->
                navController.navigateToChatRoom(
                    chatRoomId = 0,
                    enabledPlatforms = enabledPlatforms,
                    initialQuestion = initialQuestion,
                    initialModel = initialModel,
                    initialAttachmentPaths = initialAttachmentPaths,
                    initialRequestId = initialRequestId,
                    initialTextSkills = initialTextSkills
                )
            }
        ) { openDrawer, _, _ ->
            ChatScreen(
                chatViewModel = chatViewModel,
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
                onNavigateToStickerLibrary = { navController.navigate(Route.STICKER_LIBRARY) },
                onNavigateToSkills = { navController.navigate(Route.SKILLS) },
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
                onNavigationClick = { navController.navigateUp() },
                onNavigateToContextStrategy = { platformUid ->
                    navController.navigate(
                        Route.CONTEXT_STRATEGY_SETTINGS.replace("{platformUid}", platformUid)
                    )
                }
            )
        }
        composable(
            Route.CONTEXT_STRATEGY_SETTINGS,
            arguments = listOf(navArgument("platformUid") { type = NavType.StringType })
        ) { entry ->
            // Scoped to the platform settings entry so both screens share one view model: the
            // strategy page saves immediately, and the row on the parent page has to reflect that
            // as soon as the user navigates back.
            val platformUid = entry.arguments?.getString("platformUid").orEmpty()
            val parentEntry = remember(entry) {
                navController.getBackStackEntry(
                    Route.PLATFORM_SETTINGS.replace("{platformUid}", platformUid)
                )
            }
            ContextStrategyScreen(
                settingViewModel = hiltViewModel(parentEntry),
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(Route.ABOUT_PAGE) {
            AboutScreen(
                onNavigationClick = { navController.navigateUp() },
                onNavigationToLicense = { navController.navigate(Route.LICENSE) },
                onNavigateToPromptTrace = { navController.navigate(Route.PROMPT_TRACE_DEBUG) }
            )
        }
        composable(Route.PROMPT_TRACE_DEBUG) {
            PromptTraceScreen(onNavigationClick = { navController.navigateUp() })
        }
        composable(Route.MEMORY) {
            MemoryScreen(onNavigationClick = { navController.navigateUp() })
        }
        composable(Route.STICKER_LIBRARY) {
            StickerLibraryScreen(onNavigationClick = { navController.navigateUp() })
        }
        composable(Route.SKILLS) {
            SkillsScreen(onNavigationClick = { navController.navigateUp() })
        }
        composable(Route.LICENSE) {
            LicenseScreen(onNavigationClick = { navController.navigateUp() })
        }
    }
}
