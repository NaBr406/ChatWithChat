package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.model.ReasoningCapability
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.coerceReasoningModeForModel
import cn.nabr.chatwithchat.data.model.isExplicitReasoningEnabled
import cn.nabr.chatwithchat.data.model.isGptOssModel
import cn.nabr.chatwithchat.data.model.reasoningCapabilityForModel
import java.net.URI

internal fun mapReasoningMode(
    platform: PlatformV2,
    requestedMode: ReasoningMode
): ReasoningRequestParameters {
    val capability = platform.reasoningCapabilityForModel()
    val mode = platform.coerceReasoningModeForModel(requestedMode)
    val effort = mode.toEffort()

    return when (platform.compatibleType) {
        ClientType.OPENAI -> ReasoningRequestParameters(
            mode = mode,
            openAIEffort = effort.takeIf { capability.capability == ReasoningCapability.EFFORT }
        )

        ClientType.ANTHROPIC -> {
            val budgetTokens = mode.toAnthropicBudgetTokens()
            ReasoningRequestParameters(
                mode = mode,
                anthropicBudgetTokens = budgetTokens,
                anthropicMaxTokens = budgetTokens?.let { it + ANTHROPIC_RESPONSE_TOKEN_HEADROOM }
            )
        }

        ClientType.GOOGLE -> ReasoningRequestParameters(
            mode = mode,
            googleThinkingBudget = mode.toGoogleThinkingBudget(),
            googleIncludeThoughts = mode.takeIf { it.isExplicitReasoningEnabled() }?.let { true }
        )

        ClientType.GROQ -> {
            val isGptOssModel = isGptOssModel(platform.model)
            ReasoningRequestParameters(
                mode = mode,
                groqReasoningEffort = effort.takeIf { mode.isExplicitReasoningEnabled() && isGptOssModel },
                groqReasoningFormat = when {
                    mode.isExplicitReasoningEnabled() && !isGptOssModel -> "parsed"
                    mode == ReasoningMode.OFF && !isGptOssModel -> "hidden"
                    else -> null
                },
                groqIncludeReasoning = when {
                    mode.isExplicitReasoningEnabled() && isGptOssModel -> true
                    mode == ReasoningMode.OFF && isGptOssModel -> false
                    else -> null
                }
            )
        }

        ClientType.OPENROUTER, ClientType.CUSTOM -> {
            val usesOfficialDeepSeekThinking = platform.usesOfficialDeepSeekApi()
            ReasoningRequestParameters(
                mode = mode,
                openAICompatibleReasoningEffort = effort.takeIf {
                    mode.isExplicitReasoningEnabled() && capability.capability == ReasoningCapability.EFFORT
                },
                openAICompatibleThinkingType = "enabled".takeIf { usesOfficialDeepSeekThinking }
            )
        }

        ClientType.OLLAMA -> ReasoningRequestParameters(mode = mode)
    }
}

private fun ReasoningMode.toEffort(): String? = when (this) {
    ReasoningMode.LOW -> "low"
    ReasoningMode.MEDIUM -> "medium"
    ReasoningMode.HIGH,
    ReasoningMode.MAX -> "high"

    ReasoningMode.AUTO,
    ReasoningMode.OFF -> null
}

private fun ReasoningMode.toAnthropicBudgetTokens(): Int? = when (this) {
    ReasoningMode.LOW -> 4_096
    ReasoningMode.MEDIUM -> 10_000
    ReasoningMode.HIGH -> 16_000
    ReasoningMode.MAX -> 24_000
    ReasoningMode.AUTO,
    ReasoningMode.OFF -> null
}

private fun ReasoningMode.toGoogleThinkingBudget(): Int? = when (this) {
    ReasoningMode.LOW -> 1_024
    ReasoningMode.MEDIUM -> 4_096
    ReasoningMode.HIGH -> 8_192
    ReasoningMode.MAX -> 16_384
    ReasoningMode.OFF -> 0
    ReasoningMode.AUTO -> null
}

internal fun PlatformV2.usesOfficialDeepSeekApi(): Boolean {
    if (compatibleType != ClientType.CUSTOM || !model.contains("deepseek", ignoreCase = true)) return false
    val host = runCatching { URI(apiUrl.trim()).host }.getOrNull()
    return host.equals("api.deepseek.com", ignoreCase = true)
}

private const val ANTHROPIC_RESPONSE_TOKEN_HEADROOM = 4_096
