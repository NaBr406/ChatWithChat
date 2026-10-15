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
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
    private val modelResolverProvider: Provider<MemoryModelResolver>
) : MemoryModelDependencyNotifier {
    override suspend fun onDependenciesChanged() {
        runCatching {
            // Settings can change while the selected model is still unavailable. Leave those jobs
            // blocked instead of consuming another retry cycle just to fail model resolution again.
            if (modelResolverProvider.get().resolveCurrentPreference() !is MemoryModelResolution.Resolved) {
                return@runCatching
            }
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
