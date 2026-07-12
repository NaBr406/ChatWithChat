package dev.chungjungsoo.gptmobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkEnqueuer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MemoryMaintenanceRetryReceiver : BroadcastReceiver() {

    @Inject
    lateinit var maintenanceScheduler: MemoryMaintenanceScheduler

    @Inject
    lateinit var workEnqueuer: MemoryMaintenanceWorkEnqueuer

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID)?.takeIf(String::isNotBlank) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                maintenanceScheduler.retryManually(jobId)?.let { retriedJob ->
                    workEnqueuer.enqueueWork(retriedJob.family)
                }
            }
            pendingResult.finish()
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "memory_maintenance_job_id"
    }
}
