package cn.nabr.chatwithchat.data.tool

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ToolApprovalBroker @Inject constructor(
    private val coordinator: ToolApprovalCoordinator
) {
    data class PendingApproval(
        val requestId: String,
        val call: ToolCall,
        val request: ToolApprovalRequest
    )

    sealed interface Decision {
        data class WithContext(val context: ToolExecutionContext) : Decision
        data object Unavailable : Decision
    }

    private data class ActiveApproval(
        val pending: PendingApproval,
        val decision: CompletableDeferred<Decision>
    )

    private val lock = Any()
    private val queue = ArrayDeque<ActiveApproval>()
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending = _pending.asStateFlow()

    suspend fun awaitApproval(call: ToolCall): Decision {
        val request = coordinator.prepare(call).getOrElse { return Decision.Unavailable }
        val active = ActiveApproval(
            pending = PendingApproval(
                requestId = UUID.randomUUID().toString(),
                call = call,
                request = request
            ),
            decision = CompletableDeferred()
        )
        synchronized(lock) {
            queue.addLast(active)
            publishPendingLocked()
        }
        return try {
            active.decision.await()
        } finally {
            synchronized(lock) {
                queue.remove(active)
                publishPendingLocked()
            }
        }
    }

    fun approve(requestId: String) {
        complete(requestId) { active ->
            coordinator.approve(active.pending.call, active.pending.request)
                .getOrNull()
                ?.let(Decision::WithContext)
                ?: Decision.Unavailable
        }
    }

    fun deny(requestId: String) {
        complete(requestId) { active ->
            coordinator.deny(active.pending.call, active.pending.request)
                .getOrNull()
                ?.let(Decision::WithContext)
                ?: Decision.Unavailable
        }
    }

    private fun complete(
        requestId: String,
        decision: (ActiveApproval) -> Decision
    ) {
        val active = synchronized(lock) {
            val current = queue.firstOrNull { approval -> approval.pending.requestId == requestId }
                ?: return
            queue.remove(current)
            publishPendingLocked()
            current
        }
        active.decision.complete(decision(active))
    }

    private fun publishPendingLocked() {
        _pending.value = queue.firstOrNull()?.pending
    }
}
