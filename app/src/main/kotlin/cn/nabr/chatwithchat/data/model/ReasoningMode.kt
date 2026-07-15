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

fun PlatformV2.defaultReasoningMode(): ReasoningMode = ReasoningMode.AUTO

fun PlatformV2.reasoningModesForModel(modelId: String = model): List<ReasoningMode> = when (compatibleType) {
    ClientType.OPENAI -> if (isOpenAIResponsesReasoningModel(modelId)) {
        standardReasoningModes()
    } else {
        emptyList()
    }

    ClientType.ANTHROPIC -> if (isAnthropicThinkingModel(modelId)) {
        standardReasoningModes(includeMax = true)
    } else {
        emptyList()
    }

    ClientType.GOOGLE -> if (isGoogleThinkingModel(modelId)) {
        standardReasoningModes(includeMax = true)
    } else {
        emptyList()
    }

    ClientType.GROQ -> when {
        isGptOssModel(modelId) -> standardReasoningModes()
        isGroqParsedReasoningModel(modelId) -> listOf(ReasoningMode.AUTO, ReasoningMode.OFF, ReasoningMode.MEDIUM)
        else -> emptyList()
    }

    ClientType.OPENROUTER, ClientType.CUSTOM -> if (isOpenAICompatibleReasoningModel(modelId)) {
        standardReasoningModes()
    } else {
        emptyList()
    }

    ClientType.OLLAMA -> emptyList()
}

fun PlatformV2.coerceReasoningModeForModel(
    mode: ReasoningMode,
    modelId: String = model
): ReasoningMode {
    val supportedModes = reasoningModesForModel(modelId)
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

private fun standardReasoningModes(includeMax: Boolean = false): List<ReasoningMode> {
    val modes = listOf(
        ReasoningMode.AUTO,
        ReasoningMode.OFF,
        ReasoningMode.LOW,
        ReasoningMode.MEDIUM,
        ReasoningMode.HIGH
    )

    return if (includeMax) modes + ReasoningMode.MAX else modes
}

private fun isOpenAIResponsesReasoningModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return hasModelToken(normalized, "o1") ||
        hasModelToken(normalized, "o3") ||
        hasModelToken(normalized, "o4") ||
        normalized.contains("gpt-5") ||
        normalized.contains("reasoning")
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
        normalized.contains("thinking")
}

private fun isGroqParsedReasoningModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return normalized.contains("qwen3") ||
        normalized.contains("qwq") ||
        normalized.contains("deepseek-r1") ||
        normalized.contains("reasoning")
}

private fun isOpenAICompatibleReasoningModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return isOpenAIResponsesReasoningModel(normalized) ||
        isGptOssModel(normalized) ||
        isGroqParsedReasoningModel(normalized) ||
        isAnthropicThinkingModel(normalized) ||
        isGoogleThinkingModel(normalized)
}

private fun hasModelToken(modelId: String, token: String): Boolean =
    modelId == token ||
        modelId.startsWith("$token-") ||
        modelId.startsWith("$token:") ||
        modelId.contains("/$token") ||
        modelId.contains("-$token")
