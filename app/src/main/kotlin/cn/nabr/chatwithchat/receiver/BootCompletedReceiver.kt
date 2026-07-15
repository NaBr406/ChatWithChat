package cn.nabr.chatwithchat.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobFamily
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkScheduler

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
