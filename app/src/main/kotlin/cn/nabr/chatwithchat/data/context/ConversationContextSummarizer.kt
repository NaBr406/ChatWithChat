package cn.nabr.chatwithchat.data.context

/**
 * Deterministic extractive summarizer: head-and-tail turn sampling with per-message token
 * truncation. It is *not* an LLM summary and does not pretend to be one.
 *
 * Retained deliberately as the guaranteed-available fallback for when a semantic checkpoint is
 * unavailable — the compaction call failed, was cancelled, timed out, returned something
 * unparseable, or a background fold has not landed yet. It never makes a network call, so a request
 * can always be assembled and sent.
 */
internal object ConversationContextSummarizer {
    private const val HEADER = "较早对话摘要："
    private const val MIN_MESSAGE_TOKENS = 16

    fun build(
        turns: List<ConversationTurn>,
        maxTokens: Int,
        maxTurns: Int,
        tokenCounter: ContextTokenCounter
    ): String? {
        if (turns.isEmpty() || maxTokens <= 0 || maxTurns <= 0) return null

        val selectedTurns = selectSummaryTurns(turns, maxTurns)
        val lines = mutableListOf(HEADER)
        val hiddenTurnCount = turns.size - selectedTurns.size
        if (hiddenTurnCount > 0) {
            lines += "- 另有 $hiddenTurnCount 轮较早对话已压缩省略。"
        }

        selectedTurns.forEachIndexed { index, turn ->
            val remainingMessages = ((selectedTurns.size - index) * 2).coerceAtLeast(1)
            val remainingBudget = (maxTokens - tokenCounter.estimateText(lines.joinToString("\n"))).coerceAtLeast(MIN_MESSAGE_TOKENS)
            val messageBudget = (remainingBudget / remainingMessages).coerceAtLeast(MIN_MESSAGE_TOKENS)

            appendMessageLine(lines, "用户", turn.userMessage.content, messageBudget, tokenCounter)
            turn.assistantMessage?.let { assistantMessage ->
                appendMessageLine(lines, "助手", assistantMessage.semanticAssistantContent(), messageBudget, tokenCounter)
            }
            turn.visualAssetLines().forEach { line -> lines += "- $line" }
        }

        val summary = lines.joinToString("\n").trim()
        return when {
            summary == HEADER -> null
            tokenCounter.estimateText(summary) <= maxTokens -> summary
            else -> tokenCounter.truncateText(summary, maxTokens)
        }
    }

    private fun selectSummaryTurns(
        turns: List<ConversationTurn>,
        maxTurns: Int
    ): List<ConversationTurn> {
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
