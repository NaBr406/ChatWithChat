package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2

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
