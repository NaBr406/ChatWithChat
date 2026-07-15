package cn.nabr.chatwithchat.presentation

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        appNotificationManager.ensureChannels()
        applicationScope.launch {
            memoryMaintenanceStartupCoordinator.run()
        }
    }
}
