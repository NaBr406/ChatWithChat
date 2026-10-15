package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.network.isChatNetworkFailure
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector

internal suspend fun <T> FlowCollector<ApiState>.withChatNetworkReconnect(
    waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
    request: suspend FlowCollector<ApiState>.(isFinalAttempt: Boolean) -> T
): T {
    val requestId = UUID.randomUUID().toString()
    emit(ApiState.StreamCheckpoint(requestId))
    var reconnects = 0
    while (true) {
        try {
            val result = request(reconnects == MAX_CHAT_NETWORK_RECONNECTS)
            emit(ApiState.StreamCheckpointReleased(requestId))
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!error.isChatNetworkFailure() || reconnects == MAX_CHAT_NETWORK_RECONNECTS) {
                emit(ApiState.StreamCheckpointReleased(requestId))
                throw error
            }
            waitBeforeRetry(1_000L shl reconnects)
            reconnects++
            emit(ApiState.StreamRollback(requestId))
        }
    }
}

internal const val MAX_CHAT_NETWORK_RECONNECTS = 5
