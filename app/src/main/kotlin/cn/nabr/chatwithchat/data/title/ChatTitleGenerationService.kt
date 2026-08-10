package cn.nabr.chatwithchat.data.title

import cn.nabr.chatwithchat.data.context.ContextCompactionModelClient
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Generates a conversation title with one isolated LLM call against the chat's own model.
 *
 * The model is never chosen here. The caller passes the platform the conversation is already using,
 * which is what makes "默认模型就是聊天模型" true by construction: there is no separate title-model
 * setting to fall out of sync, and a chat on a local Ollama model never silently bills a cloud key.
 *
 * Every failure returns `null` rather than propagating. A title is cosmetic and the caller always
 * holds the truncated first message as a fallback, so no failure of this path may ever surface as a
 * chat error. Cancellation is the one exception and is rethrown: if the scope is gone there is
 * nobody left to name the chat for.
 *
 * The network seam is [ContextCompactionModelClient], reused rather than reimplemented. It is
 * already exactly this shape — one system prompt, one user payload, a token cap, no tool plumbing —
 * and it carries the mutex that stops concurrent auxiliary calls from interleaving `setToken` on the
 * shared API singletons. A third hand-rolled copy of the five-provider fan-out would be the actual
 * bug risk here.
 */
class ChatTitleGenerationService(
    private val modelClient: ContextCompactionModelClient
) {

    suspend fun generateTitle(platform: PlatformV2, turns: List<ChatTitleTurn>): String? {
        if (!platform.isEligibleForTitleGeneration()) return null
        val userPayload = ChatTitlePrompt.buildUserPayload(turns) ?: return null

        val timeoutSeconds = timeoutSecondsFor(platform)
        val response = try {
            withTimeoutOrNoLimit(timeoutSeconds) {
                modelClient.requestCompaction(
                    platform = platform,
                    systemPrompt = ChatTitlePrompt.SYSTEM_PROMPT,
                    userPayload = userPayload,
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    timeoutSeconds = timeoutSeconds
                )
            }
        } catch (_: TimeoutCancellationException) {
            // This service's own decision to stop waiting, not an outer cancellation, so it degrades
            // to the caller's fallback title instead of propagating.
            return null
        }

        return response.getOrNull()?.content?.let { ChatTitlePrompt.parse(it) }
    }

    /**
     * `timeout == 0` means "no limit" throughout this codebase, which [withTimeout] cannot express.
     */
    private suspend fun <T> withTimeoutOrNoLimit(timeoutSeconds: Int, block: suspend () -> T): T =
        if (timeoutSeconds <= 0) block() else withTimeout(timeoutSeconds * 1_000L) { block() }

    /**
     * Naming a chat is the lowest-value call the app makes, so it gets a short leash regardless of a
     * generous per-provider chat timeout — while still honouring the `0 == no limit` convention.
     */
    private fun timeoutSecondsFor(platform: PlatformV2): Int =
        if (platform.timeout <= 0) 0 else minOf(platform.timeout, MAX_TIMEOUT_SECONDS)

    private fun PlatformV2.isEligibleForTitleGeneration(): Boolean =
        model.isNotBlank() &&
            apiUrl.isNotBlank() &&
            (compatibleType == ClientType.OLLAMA || !token.isNullOrBlank())

    private companion object {
        /**
         * A title is at most 50 characters, but the cap cannot be that tight: reasoning models spend
         * their budget on a `<think>` block first and would be cut off before writing the answer.
         */
        const val MAX_OUTPUT_TOKENS = 512
        const val MAX_TIMEOUT_SECONDS = 30
    }
}
