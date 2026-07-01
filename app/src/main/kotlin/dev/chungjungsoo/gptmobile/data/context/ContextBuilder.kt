package dev.chungjungsoo.gptmobile.data.context

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote
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

            RawConversationTurn(
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                isCurrentTurn = index == userMessages.lastIndex
            )
        }

        val filteredTurns = rawTurns.filter { turn ->
            when {
                turn.isCurrentTurn -> true
                turn.assistantMessage == null -> false
                turn.assistantMessage.effectiveContent().isBlank() && turn.assistantMessage.attachments.isEmpty() -> false
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
            estimatedTokens = estimatedTokens
        )
    }

    private fun selectHistoryTurns(
        historyTurns: List<RawConversationTurn>,
        currentTurn: RawConversationTurn,
        policy: ProviderContextPolicy,
        tokenCounter: ContextTokenCounter
    ): List<RawConversationTurn> {
        if (historyTurns.isEmpty()) return emptyList()

        val candidates = historyTurns.takeLast(policy.recentTurnWindow)
        val selectedTurns = mutableListOf<RawConversationTurn>()

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
        omittedTurns: List<RawConversationTurn>,
        selectedTurns: List<RawConversationTurn>,
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
            sanitizedMessage.effectiveContent().isBlank() && sanitizedMessage.attachments.isEmpty() -> null
            isAssistantErrorMessage(sanitizedMessage.content) -> null
            else -> sanitizedMessage
        }
    }

    private fun applyAttachmentWindow(
        turns: List<RawConversationTurn>,
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

    private fun sanitizeAssistantMessageForContext(message: MessageV2): MessageV2 {
        val sanitizedContent = stripAssistantErrorNote(message.effectiveContent()).trimEnd()
        return if (sanitizedContent == message.content && message.activeRevisionIndex == ACTIVE_REVISION_LATEST) {
            message
        } else {
            message.copy(
                content = sanitizedContent,
                activeRevisionIndex = ACTIVE_REVISION_LATEST
            )
        }
    }

    private companion object {
        const val MIN_SUMMARY_TOKEN_BUDGET = 48
    }
}

private data class RawConversationTurn(
    val userMessage: MessageV2,
    val assistantMessage: MessageV2?,
    val isCurrentTurn: Boolean
)

private object ConversationContextSummarizer {
    private const val HEADER = "Earlier conversation summary:"
    private const val MIN_MESSAGE_TOKENS = 16

    fun build(
        turns: List<RawConversationTurn>,
        maxTokens: Int,
        maxTurns: Int,
        tokenCounter: ContextTokenCounter
    ): String? {
        if (turns.isEmpty() || maxTokens <= 0 || maxTurns <= 0) return null

        val selectedTurns = selectSummaryTurns(turns, maxTurns)
        val lines = mutableListOf(HEADER)
        val hiddenTurnCount = turns.size - selectedTurns.size
        if (hiddenTurnCount > 0) {
            lines += "- $hiddenTurnCount earlier turns are compressed out of this summary."
        }

        selectedTurns.forEachIndexed { index, turn ->
            val remainingMessages = ((selectedTurns.size - index) * 2).coerceAtLeast(1)
            val remainingBudget = (maxTokens - tokenCounter.estimateText(lines.joinToString("\n"))).coerceAtLeast(MIN_MESSAGE_TOKENS)
            val messageBudget = (remainingBudget / remainingMessages).coerceAtLeast(MIN_MESSAGE_TOKENS)

            appendMessageLine(lines, "User", turn.userMessage.content, messageBudget, tokenCounter)
            turn.assistantMessage?.let { assistantMessage ->
                appendMessageLine(lines, "Assistant", assistantMessage.effectiveContent(), messageBudget, tokenCounter)
            }
        }

        val summary = lines.joinToString("\n").trim()
        return when {
            summary == HEADER -> null
            tokenCounter.estimateText(summary) <= maxTokens -> summary
            else -> tokenCounter.truncateText(summary, maxTokens)
        }
    }

    private fun selectSummaryTurns(
        turns: List<RawConversationTurn>,
        maxTurns: Int
    ): List<RawConversationTurn> {
        if (turns.size <= maxTurns) return turns
        if (maxTurns == 1) return turns.takeLast(1)

        val headCount = minOf(2, (maxTurns / 3).coerceAtLeast(1))
        val tailCount = (maxTurns - headCount).coerceAtLeast(1)
        return turns.take(headCount) + turns.takeLast(tailCount)
    }

    private fun appendMessageLine(
        lines: MutableList<String>,
        role: String,
        text: String,
        maxTokens: Int,
        tokenCounter: ContextTokenCounter
    ) {
        val compactText = ContextTokenCounter.compactText(text)
        if (compactText.isBlank()) return

        lines += "- $role: ${tokenCounter.truncateText(compactText, maxTokens)}"
    }
}

private class ContextTokenCounter private constructor(
    private val encoding: Encoding?
) {
    fun estimateTurns(turns: List<RawConversationTurn>): Int = turns.sumOf(::estimateTurn)

    private fun estimateTurn(turn: RawConversationTurn): Int =
        TURN_OVERHEAD_TOKENS +
            estimateMessage(turn.userMessage) +
            (turn.assistantMessage?.let(::estimateMessage) ?: 0)

    fun estimateText(text: String): Int {
        if (text.isBlank()) return 0
        return runCatching { encoding?.countTokensOrdinary(text) }
            .getOrNull()
            ?: estimateTextHeuristic(text)
    }

    fun truncateText(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""

        val compactText = compactText(text)
        if (estimateText(compactText) <= maxTokens) return compactText

        val suffix = "..."
        val contentBudget = (maxTokens - estimateText(suffix)).coerceAtLeast(1)
        var low = 0
        var high = compactText.length
        var best = 0

        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = compactText.take(mid).trimEnd()
            if (estimateText(candidate) <= contentBudget) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return compactText.take(best).trimEnd() + suffix
    }

    private fun estimateMessage(message: MessageV2): Int =
        MESSAGE_OVERHEAD_TOKENS +
            estimateText(message.effectiveContent()) +
            message.attachments.size * ATTACHMENT_ESTIMATED_TOKENS

    private fun estimateTextHeuristic(text: String): Int {
        var tokens = 0
        var asciiRunLength = 0

        fun flushAsciiRun() {
            if (asciiRunLength > 0) {
                tokens += (asciiRunLength + 3) / 4
                asciiRunLength = 0
            }
        }

        text.forEach { char ->
            when {
                char.isWhitespace() -> flushAsciiRun()
                char.isCjk() -> {
                    flushAsciiRun()
                    tokens += 1
                }
                char.isLetterOrDigit() || char in ASCII_TOKEN_CHARS -> asciiRunLength += 1
                else -> {
                    flushAsciiRun()
                    tokens += 1
                }
            }
        }
        flushAsciiRun()

        return tokens.coerceAtLeast(1)
    }

    private fun Char.isCjk(): Boolean = code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xF900..0xFAFF ||
        code in 0x3040..0x30FF ||
        code in 0xAC00..0xD7AF

    companion object {
        private const val MESSAGE_OVERHEAD_TOKENS = 4
        const val TURN_OVERHEAD_TOKENS = 2
        const val ATTACHMENT_ESTIMATED_TOKENS = 850

        private val ASCII_TOKEN_CHARS = setOf('_', '-', '.', '/', ':', '@', '#')
        private val registry by lazy { Encodings.newLazyEncodingRegistry() }

        fun forPlatform(platform: PlatformV2): ContextTokenCounter = ContextTokenCounter(
            encoding = encodingForModel(platform.model) ?: fallbackEncoding(platform)
        )

        fun compactText(text: String): String = text.trim()
            .replace(Regex("\\s+"), " ")

        private fun encodingForModel(model: String): Encoding? = runCatching {
            val encoding = registry.getEncodingForModel(model)
            if (encoding.isPresent) encoding.get() else null
        }.getOrNull()

        private fun fallbackEncoding(platform: PlatformV2): Encoding? {
            val encodingType = if (platform.compatibleType == ClientType.OPENAI || platform.model.prefersO200k()) {
                EncodingType.O200K_BASE
            } else {
                EncodingType.CL100K_BASE
            }

            return runCatching { registry.getEncoding(encodingType) }.getOrNull()
        }

        private fun String.prefersO200k(): Boolean {
            val normalizedModel = lowercase()
            return normalizedModel.startsWith("gpt-4o") ||
                normalizedModel.startsWith("gpt-5") ||
                normalizedModel.startsWith("o1") ||
                normalizedModel.startsWith("o3") ||
                normalizedModel.startsWith("o4")
        }
    }
}
