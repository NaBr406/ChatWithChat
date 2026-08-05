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
            val coordinator = entryPoint.chatHistoryIndexCoordinator()
            var hasMore = true
            var disabled = false
            var pass = 0
            while (hasMore && !disabled && pass < MAX_PASSES) {
                val result = coordinator.processWork()
                disabled = result.disabled
                hasMore = result.hasMore
                pass++
            }
            if (disabled || !hasMore) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IllegalArgumentException) {
            ListenableWorker.Result.failure()
        } catch (_: Throwable) {
            ListenableWorker.Result.retry()
        }
    }

    private companion object {
        const val MAX_PASSES = 32
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatHistoryWorkerEntryPoint {
    fun chatHistoryIndexCoordinator(): ChatHistoryIndexCoordinator
}
