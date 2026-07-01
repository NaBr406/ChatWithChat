package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.model.ClientType

data class ProviderContextPolicy(
    val recentTurnWindow: Int,
    val historicalImageTurnWindow: Int,
    val maxInlineAttachmentBytes: Long? = null,
    val preferProviderFileRefs: Boolean = false,
    val maxContextTokens: Int,
    val summaryTokenBudget: Int,
    val maxSummaryTurns: Int = 8
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
                    summaryTokenBudget = 1_200
                )
            }

            ClientType.ANTHROPIC -> {
                ProviderContextPolicy(
                    recentTurnWindow = 10,
                    historicalImageTurnWindow = 10,
                    preferProviderFileRefs = true,
                    maxContextTokens = 24_000,
                    summaryTokenBudget = 1_200
                )
            }

            ClientType.GOOGLE -> {
                ProviderContextPolicy(
                    recentTurnWindow = 10,
                    historicalImageTurnWindow = 10,
                    preferProviderFileRefs = true,
                    maxContextTokens = 24_000,
                    summaryTokenBudget = 1_200
                )
            }

            ClientType.GROQ -> {
                ProviderContextPolicy(
                    recentTurnWindow = 8,
                    historicalImageTurnWindow = 0,
                    maxInlineAttachmentBytes = INLINE_ATTACHMENT_LIMIT_BYTES,
                    maxContextTokens = 8_000,
                    summaryTokenBudget = 800
                )
            }

            ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM -> {
                ProviderContextPolicy(
                    recentTurnWindow = 6,
                    historicalImageTurnWindow = 0,
                    maxInlineAttachmentBytes = INLINE_ATTACHMENT_LIMIT_BYTES,
                    maxContextTokens = 6_000,
                    summaryTokenBudget = 700
                )
            }
        }
    }
}
