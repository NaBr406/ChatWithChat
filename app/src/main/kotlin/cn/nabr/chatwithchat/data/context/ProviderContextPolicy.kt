package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.model.ClientType

data class ProviderContextPolicy(
    val recentTurnWindow: Int,
    val historicalImageTurnWindow: Int,
    val maxInlineAttachmentBytes: Long? = null,
    val preferProviderFileRefs: Boolean = false,
    // Local construction budget for the legacy extractive path. This is NOT the model's context
    // window and must never be fed into the Task 1 budget formula -- doing so would make
    // RAW_PREFERRED compact immediately. Use fallbackModelContextWindow / ContextWindowResolver.
    val maxContextTokens: Int,
    val summaryTokenBudget: Int,
    val maxSummaryTurns: Int = 8,
    // Conservative assumption for "this provider, unknown model" when no window was discovered.
    // Last resort in ContextWindowResolver's three-step order.
    val fallbackModelContextWindow: Int
) {
    companion object {
        private const val INLINE_ATTACHMENT_LIMIT_BYTES = 12L * 1024 * 1024

        fun forClientType(clientType: ClientType): ProviderContextPolicy = when (clientType) {
            ClientType.OPENAI -> {
                ProviderContextPolicy(
                    recentTurnWindow = 10,
                    historicalImageTurnWindow = 10,
                    preferProviderFileRefs = true,
                    maxContextTokens = 24_000,
                    summaryTokenBudget = 1_200,
                    fallbackModelContextWindow = 128_000
                )
            }

            ClientType.ANTHROPIC -> {
                ProviderContextPolicy(
                    recentTurnWindow = 10,
                    historicalImageTurnWindow = 10,
                    preferProviderFileRefs = true,
                    maxContextTokens = 24_000,
                    summaryTokenBudget = 1_200,
                    fallbackModelContextWindow = 200_000
                )
            }

            ClientType.GOOGLE -> {
                ProviderContextPolicy(
                    recentTurnWindow = 10,
                    historicalImageTurnWindow = 10,
                    preferProviderFileRefs = true,
                    maxContextTokens = 24_000,
                    summaryTokenBudget = 1_200,
                    fallbackModelContextWindow = 32_768
                )
            }

            ClientType.GROQ -> {
                ProviderContextPolicy(
                    recentTurnWindow = 8,
                    historicalImageTurnWindow = 0,
                    maxInlineAttachmentBytes = INLINE_ATTACHMENT_LIMIT_BYTES,
                    maxContextTokens = 8_000,
                    summaryTokenBudget = 800,
                    fallbackModelContextWindow = 8_192
                )
            }

            // Ollama's effective window is decided by the server's OLLAMA_CONTEXT_LENGTH /
            // Modelfile num_ctx, which the OpenAI-compatible endpoint cannot query or set. The
            // default is 4096 and overflow is silently truncated, so 6_000 is deliberately close
            // to that floor rather than to any architectural limit the model advertises.
            ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM -> {
                ProviderContextPolicy(
                    recentTurnWindow = 6,
                    historicalImageTurnWindow = 0,
                    maxInlineAttachmentBytes = INLINE_ATTACHMENT_LIMIT_BYTES,
                    maxContextTokens = 6_000,
                    summaryTokenBudget = 700,
                    fallbackModelContextWindow = 6_000
                )
            }
        }
    }
}
