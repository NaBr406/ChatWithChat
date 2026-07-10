package dev.chungjungsoo.gptmobile.presentation

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceRepairer
import dev.chungjungsoo.gptmobile.data.notification.AppNotificationManager
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import dev.chungjungsoo.gptmobile.di.ApplicationScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class GPTMobileApp : Application() {
    // TODO Delete when https://github.com/google/dagger/issues/3601 is resolved.
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var memoryMaintenanceRepairer: MemoryMaintenanceRepairer

    @Inject
    lateinit var memoryRepository: MemoryRepository

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        appNotificationManager.ensureChannels()
        applicationScope.launch {
            memoryRepository.migrateActiveMemoriesToMarkdown()
            memoryMaintenanceRepairer.repairAndEnqueue()
        }
    }
}
