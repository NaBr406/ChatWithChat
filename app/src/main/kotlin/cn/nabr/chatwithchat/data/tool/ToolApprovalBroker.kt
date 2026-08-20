package cn.nabr.chatwithchat.data.tool

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ToolApprovalBroker @Inject constructor(
    private val coordinator: ToolApprovalCoordinator,
    private val sessionGrants: ToolApprovalSessionGrants = ToolApprovalSessionGrants()
) {
    data class PendingApproval(
        val requestId: String,
        val call: ToolCall,
        val request: ToolApprovalRequest,
        /**
         * Whether this tool allows "allow for this conversation".
         *
         * False for a tool that did not opt in, and false outside a persisted conversation, so the UI never
         * offers a grant that could not be honoured.
         */
        val canGrantForSession: Boolean = false
    )

    sealed interface Decision {
        data class WithContext(val context: ToolExecutionContext) : Decision
        data object Unavailable : Decision
    }

    private data class ActiveApproval(
        val pending: PendingApproval,
        val chatId: Int,
        val scopeKey: String?,
        val decision: CompletableDeferred<Decision>
    )

    private val lock = Any()
    private val queue = ArrayDeque<ActiveApproval>()
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending = _pending.asStateFlow()

    /**
     * Obtains authorization for [call], prompting only when no conversation-scoped grant already covers it.
     *
     * [chatId] of zero means "no conversation scope" — grants are neither consulted nor offered, so a
     * caller that cannot identify the conversation falls back to strict per-call approval.
     */
    suspend fun awaitApproval(call: ToolCall, chatId: Int = 0): Decision {
        val scopeKey = coordinator.sessionApprovalScope(call.name)
        if (scopeKey != null && sessionGrants.isGranted(chatId, scopeKey)) {
            return coordinator.approveFromSessionGrant(call)
                .getOrNull()
                ?.let(Decision::WithContext)
                ?: Decision.Unavailable
        }

        val request = coordinator.prepare(call).getOrElse { return Decision.Unavailable }
        val active = ActiveApproval(
            pending = PendingApproval(
                requestId = UUID.randomUUID().toString(),
                call = call,
                request = request,
                canGrantForSession = scopeKey != null && chatId > 0
            ),
            chatId = chatId,
            scopeKey = scopeKey,
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

    /**
     * Approves this call and records a grant so the rest of the conversation is not asked again.
     *
     * The grant is stored only once this call's own approval succeeds, so a rejected authorization cannot
     * leave a grant behind. Falls back to a plain one-off approval if the tool never offered a grant.
     */
    fun approveForSession(requestId: String) {
        complete(requestId) { active ->
            val decision = coordinator.approve(active.pending.call, active.pending.request)
                .getOrNull()
                ?.let(Decision::WithContext)
                ?: Decision.Unavailable
            if (decision is Decision.WithContext && active.pending.canGrantForSession) {
                active.scopeKey?.let { scope -> sessionGrants.grant(active.chatId, scope) }
            }
            decision
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
