package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

data class ConversationTurn(
    val userMessage: MessageV2,
    val assistantMessage: MessageV2?,
    val isCurrentTurn: Boolean
)

data class ConversationContext(
    val turns: List<ConversationTurn> = emptyList(),
    val summary: String? = null,
    val estimatedTokens: Int = 0,
    val omittedTurns: List<ConversationTurn> = emptyList()
)
