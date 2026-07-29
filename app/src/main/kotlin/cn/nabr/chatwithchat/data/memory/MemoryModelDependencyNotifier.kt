package cn.nabr.chatwithchat.data.memory

import android.util.Log
import javax.inject.Provider

fun interface MemoryModelDependencyNotifier {
    suspend fun onDependenciesChanged()

    data object None : MemoryModelDependencyNotifier {
        override suspend fun onDependenciesChanged() = Unit
    }
}

class SchedulerMemoryModelDependencyNotifier(
    private val schedulerProvider: Provider<MemoryMaintenanceScheduler>,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer
) : MemoryModelDependencyNotifier {
    override suspend fun onDependenciesChanged() {
        runCatching {
            val reopenedCount = schedulerProvider.get().reopenMemoryModelBlockedJobs()
            if (reopenedCount > 0) {
                workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
            }
        }.onFailure { throwable ->
            runCatching { Log.w(TAG, "Memory model dependency repair failed", throwable) }
        }
    }

    private companion object {
        const val TAG = "MemoryModelDependency"
    }
}
