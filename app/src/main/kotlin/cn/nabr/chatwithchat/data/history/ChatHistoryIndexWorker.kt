package cn.nabr.chatwithchat.data.history

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

class ChatHistoryIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val entryPoint = try {
            EntryPointAccessors.fromApplication(applicationContext, ChatHistoryWorkerEntryPoint::class.java)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return ListenableWorker.Result.retry()
        }
        return try {
            var keepRunning = true
            var iterations = 0
            while (keepRunning && iterations++ < MAX_ITERATIONS) {
                keepRunning = entryPoint.chatHistoryIndexProcessor().process()
            }
            ListenableWorker.Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            ListenableWorker.Result.retry()
        }
    }

    private companion object {
        const val MAX_ITERATIONS = 8
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatHistoryWorkerEntryPoint {
    fun chatHistoryIndexProcessor(): ChatHistoryIndexProcessor
}
