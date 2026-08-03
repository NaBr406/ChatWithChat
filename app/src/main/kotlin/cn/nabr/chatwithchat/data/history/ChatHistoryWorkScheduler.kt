package cn.nabr.chatwithchat.data.history

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

fun interface ChatHistoryWorkEnqueuer {
    fun enqueue()

    fun enqueueRebuild() = enqueue()
}

class ChatHistoryWorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ChatHistoryWorkEnqueuer {
    override fun enqueue() {
        enqueueWork(
            policy = ExistingWorkPolicy.KEEP,
            inputData = Data.EMPTY
        )
    }

    override fun enqueueRebuild() {
        enqueueWork(
            policy = ExistingWorkPolicy.REPLACE,
            inputData = Data.Builder().putBoolean(ChatHistoryIndexWorker.INPUT_REBUILD, true).build()
        )
    }

    private fun enqueueWork(policy: ExistingWorkPolicy, inputData: Data) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<ChatHistoryIndexWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "chat_history_index_v1"
    }
}
