package dev.chungjungsoo.gptmobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobFamily
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkScheduler

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MemoryMaintenanceWorkScheduler.enqueueWork(
                context = context.applicationContext,
                family = MemoryMaintenanceJobFamily.REPAIR
            )
        }
    }
}
