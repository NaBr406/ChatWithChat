package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.visual.VisualAssetCatalogItem

data class ConversationTurn(
    val userMessage: MessageV2,
    val assistantMessage: MessageV2?,
    val isCurrentTurn: Boolean,
    val visualAssets: List<VisualAssetCatalogItem> = emptyList()
)

data class ConversationContext(
    val turns: List<ConversationTurn> = emptyList(),
    val summary: String? = null,
    val estimatedTokens: Int = 0,
    val omittedTurns: List<ConversationTurn> = emptyList()
)

internal fun ConversationTurn.visualAssetLines(): List<String> =
    visualAssets
        .sortedWith(compareBy({ it.messageId }, { it.attachmentIndex }, { it.visualRef }))
        .map { entry ->
            buildString {
                append("[visual asset ref=")
                append(entry.visualRef)
                append(" hash=sha256:")
                append(entry.assetHash)
                append(" turn=")
                append(entry.turnOrdinal)
                append(" role=")
                append(entry.role)
                append(" attachment_index=")
                append(entry.attachmentIndex)
                append(" profile=")
                append(entry.compactedProfile ?: "ORIGINAL")
                append(" name=")
                append(entry.displayName)
                append(" mime=")
                append(entry.mimeType)
                append("]")
            }
        }
