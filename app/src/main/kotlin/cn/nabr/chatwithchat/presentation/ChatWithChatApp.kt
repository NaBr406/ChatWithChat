package cn.nabr.chatwithchat.presentation

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import cn.nabr.chatwithchat.data.mcp.McpToolRegistrar
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceStartupCoordinator
import cn.nabr.chatwithchat.data.notification.AppNotificationManager
import cn.nabr.chatwithchat.di.ApplicationScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class ChatWithChatApp : Application() {
    // TODO Delete when https://github.com/google/dagger/issues/3601 is resolved.
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var memoryMaintenanceStartupCoordinator: MemoryMaintenanceStartupCoordinator

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    @Inject
    lateinit var mcpToolRegistrar: McpToolRegistrar

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        appNotificationManager.ensureChannels()
        applicationScope.launch {
            memoryMaintenanceStartupCoordinator.run()
        }
        applicationScope.launch {
            // Rehydrate MCP tools from the cached catalog. Deliberately offline: a tool the user enabled
            // must be in the registry before the first message, without waiting on a server that may be
            // unreachable. A live `tools/list` refresh is a separate, user- or settings-triggered pass.
            mcpToolRegistrar.refreshInstalledTools()
        }
    }
}
