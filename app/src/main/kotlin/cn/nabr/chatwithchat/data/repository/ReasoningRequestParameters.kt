package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.isExplicitReasoningEnabled

internal data class ReasoningRequestParameters(
    val mode: ReasoningMode,
    val openAIEffort: String? = null,
    val anthropicBudgetTokens: Int? = null,
    val anthropicMaxTokens: Int? = null,
    val googleThinkingBudget: Int? = null,
    val googleIncludeThoughts: Boolean? = null,
    val groqReasoningEffort: String? = null,
    val groqReasoningFormat: String? = null,
    val groqIncludeReasoning: Boolean? = null,
    val openAICompatibleReasoningEffort: String? = null
) {
    val hasExplicitReasoning: Boolean = mode.isExplicitReasoningEnabled()
}
