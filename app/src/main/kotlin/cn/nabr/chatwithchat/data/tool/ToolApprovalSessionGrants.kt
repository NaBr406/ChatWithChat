package cn.nabr.chatwithchat.data.tool

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversation-scoped approval grants: "allow this capability for the rest of this conversation".
 *
 * A grant lets [ToolApprovalBroker] skip the confirmation *prompt*. It never skips the cryptographic
 * binding: a fresh [ToolExecutionContext] is still minted per call and still bound to that call's exact
 * tool name and canonical arguments, so a grant cannot be replayed onto a different operation. What the
 * user gives up by granting is being asked again, not the guarantee that the token matches the call.
 *
 * Scope keys come from [ToolProvider.sessionApprovalScope] and are deliberately narrow — one server and
 * one tool — so approving a menu lookup never authorizes placing an order.
 *
 * Grants are in-memory only and die with the process. That is the intended lifetime: a durable grant
 * would be an ambient permission the user cannot see, and there is no UI to audit or revoke one.
 */
@Singleton
class ToolApprovalSessionGrants @Inject constructor() {
    private val lock = Any()
    private val grantsByChat = linkedMapOf<Int, MutableSet<String>>()

    fun isGranted(chatId: Int, scopeKey: String): Boolean {
        if (chatId <= 0 || scopeKey.isBlank()) return false
        return synchronized(lock) { grantsByChat[chatId]?.contains(scopeKey) == true }
    }

    fun grant(chatId: Int, scopeKey: String) {
        if (chatId <= 0 || scopeKey.isBlank()) return
        synchronized(lock) {
            // Oldest conversation first, so a long-lived process cannot accumulate grants without bound.
            while (grantsByChat.size >= MAX_TRACKED_CHATS && grantsByChat.keys.firstOrNull() != chatId) {
                grantsByChat.remove(grantsByChat.keys.first())
            }
            val scopes = grantsByChat.getOrPut(chatId) { linkedSetOf() }
            if (scopes.size < MAX_SCOPES_PER_CHAT) scopes += scopeKey
        }
    }

    /** Drops every grant for one conversation. */
    fun revokeChat(chatId: Int) {
        synchronized(lock) { grantsByChat.remove(chatId) }
    }

    /**
     * Drops grants whose key starts with [scopeKeyPrefix], across every conversation.
     *
     * Used when a server or one of its tools is switched off: the tool would already be rejected as
     * inactive, but leaving a grant behind would silently re-arm it if the user turned the tool back on.
     */
    fun revokeByPrefix(scopeKeyPrefix: String) {
        if (scopeKeyPrefix.isBlank()) return
        synchronized(lock) {
            grantsByChat.values.forEach { scopes ->
                scopes.removeAll { scope -> scope.startsWith(scopeKeyPrefix) }
            }
            grantsByChat.entries.removeAll { entry -> entry.value.isEmpty() }
        }
    }

    private companion object {
        private const val MAX_TRACKED_CHATS = 32
        private const val MAX_SCOPES_PER_CHAT = 64
    }
}
