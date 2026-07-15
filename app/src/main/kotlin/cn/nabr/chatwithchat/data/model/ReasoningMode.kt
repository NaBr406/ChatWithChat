package cn.nabr.chatwithchat.data.model

import cn.nabr.chatwithchat.data.database.entity.PlatformV2

enum class ReasoningMode(val storageValue: String) {
    AUTO("auto"),
    OFF("off"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    MAX("max");

    companion object {
        fun fromStorageValue(value: String?): ReasoningMode = entries.firstOrNull { mode ->
            mode.storageValue == value?.trim()?.lowercase()
        } ?: AUTO

        fun fromLegacyReasoning(enabled: Boolean): ReasoningMode = if (enabled) MEDIUM else OFF
    }
}

data class ChatPlatformConfig(
    val platformUid: String,
    val model: String,
    val reasoningMode: ReasoningMode
)

enum class ReasoningCapability {
    UNKNOWN,
    DEFAULT_ONLY,
    TOGGLE,
    EFFORT,
    UNSUPPORTED
}

data class ReasoningCapabilityProfile(
    val capability: ReasoningCapability,
    val supportedModes: List<ReasoningMode> = emptyList()
) {
    val isConfigurable: Boolean = supportedModes.isNotEmpty()
}

fun PlatformV2.defaultReasoningMode(): ReasoningMode = ReasoningMode.AUTO

fun PlatformV2.reasoningCapabilityForModel(modelId: String = model): ReasoningCapabilityProfile = when (compatibleType) {
    ClientType.OPENAI -> when {
        isOpenAIResponsesReasoningModel(modelId) -> effortCapability()
        isDefaultOnlyReasoningModel(modelId) -> defaultOnlyCapability()
        isKnownOrdinaryModel(modelId) -> unsupportedCapability()
        else -> unknownCapability()
    }

    ClientType.ANTHROPIC -> when {
        isAnthropicThinkingModel(modelId) -> effortCapability(includeMax = true, includeOff = true)
        isDefaultOnlyReasoningModel(modelId) -> defaultOnlyCapability()
        isKnownOrdinaryModel(modelId) -> unsupportedCapability()
        else -> unknownCapability()
    }

    ClientType.GOOGLE -> when {
        isGoogleThinkingModel(modelId) -> effortCapability(includeMax = true, includeOff = true)
        isDefaultOnlyReasoningModel(modelId) -> defaultOnlyCapability()
        isKnownOrdinaryModel(modelId) -> unsupportedCapability()
        else -> unknownCapability()
    }

    ClientType.GROQ -> when {
        isGptOssModel(modelId) -> effortCapability(includeOff = true)
        isGroqParsedReasoningModel(modelId) -> toggleCapability()
        isDefaultOnlyReasoningModel(modelId) -> defaultOnlyCapability()
        isKnownOrdinaryModel(modelId) -> unsupportedCapability()
        else -> unknownCapability()
    }

    ClientType.OPENROUTER, ClientType.CUSTOM, ClientType.OLLAMA -> when {
        isRecognizedReasoningFamily(modelId) -> defaultOnlyCapability()
        isKnownOrdinaryModel(modelId) -> unsupportedCapability()
        else -> unknownCapability()
    }
}

fun PlatformV2.reasoningModesForModel(modelId: String = model): List<ReasoningMode> =
    reasoningCapabilityForModel(modelId).supportedModes

fun PlatformV2.coerceReasoningModeForModel(
    mode: ReasoningMode,
    modelId: String = model
): ReasoningMode {
    val supportedModes = reasoningCapabilityForModel(modelId).supportedModes
    if (supportedModes.isEmpty()) return ReasoningMode.AUTO

    return mode.takeIf { it in supportedModes }
        ?: defaultReasoningMode().takeIf { it in supportedModes }
        ?: ReasoningMode.AUTO
}

fun ReasoningMode.isExplicitReasoningEnabled(): Boolean = when (this) {
    ReasoningMode.LOW,
    ReasoningMode.MEDIUM,
    ReasoningMode.HIGH,
    ReasoningMode.MAX -> true

    ReasoningMode.AUTO,
    ReasoningMode.OFF -> false
}

fun isGptOssModel(modelId: String): Boolean = modelId.contains("gpt-oss", ignoreCase = true)

private fun effortCapability(
    includeMax: Boolean = false,
    includeOff: Boolean = false
): ReasoningCapabilityProfile {
    val modes = buildList {
        add(ReasoningMode.AUTO)
        if (includeOff) add(ReasoningMode.OFF)
        add(ReasoningMode.LOW)
        add(ReasoningMode.MEDIUM)
        add(ReasoningMode.HIGH)
        if (includeMax) add(ReasoningMode.MAX)
    }

    return ReasoningCapabilityProfile(
        capability = ReasoningCapability.EFFORT,
        supportedModes = modes
    )
}

private fun toggleCapability(): ReasoningCapabilityProfile = ReasoningCapabilityProfile(
    capability = ReasoningCapability.TOGGLE,
    supportedModes = listOf(ReasoningMode.AUTO, ReasoningMode.OFF, ReasoningMode.MEDIUM)
)

private fun defaultOnlyCapability(): ReasoningCapabilityProfile =
    ReasoningCapabilityProfile(ReasoningCapability.DEFAULT_ONLY)

private fun unsupportedCapability(): ReasoningCapabilityProfile =
    ReasoningCapabilityProfile(ReasoningCapability.UNSUPPORTED)

private fun unknownCapability(): ReasoningCapabilityProfile =
    ReasoningCapabilityProfile(ReasoningCapability.UNKNOWN)

private fun isRecognizedReasoningFamily(modelId: String): Boolean =
    isOpenAIResponsesReasoningModel(modelId) ||
        isGptOssModel(modelId) ||
        isGroqParsedReasoningModel(modelId) ||
        isAnthropicThinkingModel(modelId) ||
        isGoogleThinkingModel(modelId) ||
        isDeepSeekModel(modelId)

private fun isOpenAIResponsesReasoningModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return hasModelToken(normalized, "o1") ||
        hasModelToken(normalized, "o3") ||
        hasModelToken(normalized, "o4") ||
        normalized.contains("gpt-5")
}

private fun isAnthropicThinkingModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return normalized.contains("claude-3-7") ||
        normalized.contains("claude-3.7") ||
        normalized.contains("claude-sonnet-4") ||
        normalized.contains("claude-opus-4") ||
        normalized.contains("claude-4") ||
        normalized.contains("claude-5")
}

private fun isGoogleThinkingModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return normalized.contains("gemini-2.5") ||
        normalized.contains("gemini-3") ||
        (
            normalized.contains("gemini") &&
                normalized.contains("thinking")
            )
}

private fun isGroqParsedReasoningModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return normalized.contains("qwen3") ||
        normalized.contains("qwq") ||
        normalized.contains("deepseek-r1")
}

private fun isDefaultOnlyReasoningModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return isDeepSeekModel(normalized) ||
        isGptOssModel(normalized) ||
        isGroqParsedReasoningModel(normalized) ||
        isAnthropicThinkingModel(normalized) ||
        isGoogleThinkingModel(normalized)
}

private fun isDeepSeekModel(modelId: String): Boolean =
    modelId.contains("deepseek", ignoreCase = true)

private fun isKnownOrdinaryModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return normalized.contains("gpt-4o") ||
        normalized.contains("gpt-4.1") ||
        normalized.contains("gpt-4-turbo") ||
        normalized.contains("gpt-3.5") ||
        normalized.contains("claude-3-5") ||
        normalized.contains("claude-3.5") ||
        normalized.contains("claude-3-haiku") ||
        normalized.contains("claude-3-sonnet") ||
        normalized.contains("claude-3-opus") ||
        normalized.contains("gemini-1.") ||
        normalized.contains("gemini-2.0") ||
        normalized.contains("llama-") ||
        normalized.contains("mistral") ||
        normalized.contains("mixtral") ||
        normalized.contains("gemma")
}

private fun hasModelToken(modelId: String, token: String): Boolean =
    modelId == token ||
        modelId.startsWith("$token-") ||
        modelId.startsWith("$token:") ||
        modelId.contains("/$token") ||
        modelId.contains("-$token")
