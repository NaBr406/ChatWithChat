package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.ACTIVE_REVISION_LATEST
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.SafeMessageSourceTarget
import cn.nabr.chatwithchat.data.database.entity.safeDedupeKey
import cn.nabr.chatwithchat.data.database.entity.safeNavigationTarget
import cn.nabr.chatwithchat.util.isAssistantErrorMessage
import cn.nabr.chatwithchat.util.stripAssistantErrorNote
import javax.inject.Inject

class ContextBuilder @Inject constructor() {
    fun build(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        policy: ProviderContextPolicy = ProviderContextPolicy.forClientType(platform.compatibleType)
    ): List<ConversationTurn> = buildContext(
        userMessages = userMessages,
        assistantMessages = assistantMessages,
        platform = platform,
        policy = policy
    ).turns

    fun buildContext(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        policy: ProviderContextPolicy = ProviderContextPolicy.forClientType(platform.compatibleType)
    ): ConversationContext {
        if (userMessages.isEmpty()) return ConversationContext()

        val rawTurns = userMessages.mapIndexed { index, userMessage ->
            val assistantMessage = assistantMessages.getOrNull(index)
                ?.firstValidAssistantCandidate(platform.uid)

            ConversationTurn(
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                isCurrentTurn = index == userMessages.lastIndex
            )
        }

        val filteredTurns = rawTurns.filter { turn ->
            when {
                turn.isCurrentTurn -> true
                turn.assistantMessage == null -> false
                turn.assistantMessage.semanticAssistantContent().isBlank() && turn.assistantMessage.attachments.isEmpty() -> false
                else -> true
            }
        }

        if (filteredTurns.isEmpty()) return ConversationContext()

        val tokenCounter = ContextTokenCounter.forPlatform(platform)
        val currentTurn = filteredTurns.lastOrNull { it.isCurrentTurn }
        val historyTurns = filteredTurns.filterNot { it.isCurrentTurn }
        val selectedHistoryTurns = currentTurn?.let { selectHistoryTurns(historyTurns, it, policy, tokenCounter) }
            ?: historyTurns.takeLast(policy.recentTurnWindow)

        val selectedTurns = buildList {
            addAll(selectedHistoryTurns)
            currentTurn?.let { add(it) }
        }
        val omittedTurns = historyTurns.dropLast(selectedHistoryTurns.size)
        val summary = buildSummaryForOmittedTurns(omittedTurns, selectedTurns, policy, tokenCounter)
        val estimatedTokens = tokenCounter.estimateTurns(selectedTurns) + tokenCounter.estimateText(summary.orEmpty())

        return ConversationContext(
            turns = applyAttachmentWindow(selectedTurns, policy),
            summary = summary,
            estimatedTokens = estimatedTokens,
            omittedTurns = omittedTurns.toConversationTurnsWithoutAttachments()
        )
    }

    private fun selectHistoryTurns(
        historyTurns: List<ConversationTurn>,
        currentTurn: ConversationTurn,
        policy: ProviderContextPolicy,
        tokenCounter: ContextTokenCounter
    ): List<ConversationTurn> {
        if (historyTurns.isEmpty()) return emptyList()

        val candidates = historyTurns.takeLast(policy.recentTurnWindow)
        val selectedTurns = mutableListOf<ConversationTurn>()

        for (candidate in candidates.asReversed()) {
            val trialTurns = buildList {
                add(candidate)
                addAll(selectedTurns)
                add(currentTurn)
            }
            val omittedCount = historyTurns.size - (selectedTurns.size + 1)
            val reservedSummaryTokens = if (omittedCount > 0) {
                minOf(policy.summaryTokenBudget, policy.maxContextTokens / 4)
            } else {
                0
            }
            val allowedTokenBudget = (policy.maxContextTokens - reservedSummaryTokens).coerceAtLeast(0)

            if (tokenCounter.estimateTurns(trialTurns) <= allowedTokenBudget) {
                selectedTurns.add(0, candidate)
            } else {
                break
            }
        }

        return selectedTurns
    }

    private fun buildSummaryForOmittedTurns(
        omittedTurns: List<ConversationTurn>,
        selectedTurns: List<ConversationTurn>,
        policy: ProviderContextPolicy,
        tokenCounter: ContextTokenCounter
    ): String? {
        if (omittedTurns.isEmpty()) return null

        val selectedTokenCount = tokenCounter.estimateTurns(selectedTurns)
        val summaryBudget = minOf(policy.summaryTokenBudget, policy.maxContextTokens - selectedTokenCount)
        if (summaryBudget < MIN_SUMMARY_TOKEN_BUDGET) return null

        return ConversationContextSummarizer.build(
            turns = omittedTurns,
            maxTokens = summaryBudget,
            maxTurns = policy.maxSummaryTurns,
            tokenCounter = tokenCounter
        )
    }

    private fun List<MessageV2>.firstValidAssistantCandidate(platformUid: String): MessageV2? = firstNotNullOfOrNull { message ->
        if (message.platformType != platformUid) return@firstNotNullOfOrNull null

        val sanitizedMessage = sanitizeAssistantMessageForContext(message)
        when {
            sanitizedMessage.semanticAssistantContent().isBlank() && sanitizedMessage.attachments.isEmpty() -> null
            isAssistantErrorMessage(sanitizedMessage.content) -> null
            else -> sanitizedMessage
        }
    }

    private fun applyAttachmentWindow(
        turns: List<ConversationTurn>,
        policy: ProviderContextPolicy
    ): List<ConversationTurn> {
        if (turns.isEmpty()) return emptyList()

        val lastIndex = turns.lastIndex
        return turns.mapIndexed { index, turn ->
            val shouldKeepAttachments = (lastIndex - index) <= policy.historicalImageTurnWindow
            val userMessage = if (shouldKeepAttachments) {
                turn.userMessage
            } else {
                turn.userMessage.copy(attachments = emptyList())
            }

            val assistantMessage = turn.assistantMessage?.let { message ->
                if (shouldKeepAttachments) {
                    message
                } else {
                    message.copy(attachments = emptyList())
                }
            }

            ConversationTurn(
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                isCurrentTurn = turn.isCurrentTurn
            )
        }
    }

    private fun List<ConversationTurn>.toConversationTurnsWithoutAttachments(): List<ConversationTurn> =
        map { turn ->
            ConversationTurn(
                userMessage = turn.userMessage.copy(attachments = emptyList()),
                assistantMessage = turn.assistantMessage?.copy(attachments = emptyList()),
                isCurrentTurn = false
            )
        }

    private fun sanitizeAssistantMessageForContext(message: MessageV2): MessageV2 {
        val sanitizedContent = stripAssistantErrorNote(message.semanticAssistantContent()).trimEnd()
            .withSourceContext(message.sourceMetadata)
        return if (sanitizedContent == message.content && message.activeRevisionIndex == ACTIVE_REVISION_LATEST) {
            message
        } else {
            message.copy(
                content = sanitizedContent,
                activeRevisionIndex = ACTIVE_REVISION_LATEST
            )
        }
    }

    private fun String.withSourceContext(sources: List<MessageSourceMetadata>): String {
        val sourceLines = sources
            .mapNotNull { source -> source.safeDedupeKey()?.let { key -> key to source } }
            .distinctBy { (key, _) -> key }
            .map { (_, source) -> source }
            .take(MAX_CONTEXT_SOURCES)
            .mapIndexed { index, source ->
                buildString {
                    val target = source.safeNavigationTarget() ?: return@buildString
                    append("${index + 1}. ")
                    append(source.title.trim().ifBlank { source.sourceToolName })
                    append(" - ")
                    append(
                        when (target) {
                            is SafeMessageSourceTarget.PublicUrl -> target.url
                            is SafeMessageSourceTarget.LocalApp ->
                                "app:${target.navigationTarget.name.lowercase()}:${target.entityId}"
                        }
                    )
                    source.snippet.trim().takeIf { it.isNotBlank() }?.let { snippet ->
                        append(" | ")
                        append(snippet.replace(Regex("\\s+"), " ").take(MAX_SOURCE_SNIPPET_CHARS))
                    }
                }
            }
        if (sourceLines.isEmpty()) return this

        val sourceBlock = buildString {
            appendLine("此回答引用的来源：")
            sourceLines.forEach { line -> appendLine(line) }
        }.trim()

        return listOf(this.trimEnd(), sourceBlock)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n")
    }

    private companion object {
        const val MIN_SUMMARY_TOKEN_BUDGET = 48
        const val MAX_CONTEXT_SOURCES = 5
        const val MAX_SOURCE_SNIPPET_CHARS = 240
    }
}
