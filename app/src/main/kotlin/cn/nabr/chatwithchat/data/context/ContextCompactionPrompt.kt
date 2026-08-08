package cn.nabr.chatwithchat.data.context

/**
 * The app-side compaction prompt and its output contract.
 *
 * This is the *semantic* compaction path: a real LLM call that produces readable checkpoint text.
 * It is used by every provider except an official OpenAI model that passed
 * [NativeCompactionModelGate], whose folding happens server-side inside the ordinary request and
 * must never see this prompt.
 *
 * The output format is stable sectioned text rather than JSON. Both were permitted; sections win
 * because compaction runs on whatever model the user happens to be chatting with, including small
 * local ones, and a model that fumbles JSON syntax produces a total loss whereas a model that
 * fumbles a section header still produces usable prose. Validation is correspondingly tolerant:
 * [parse] rejects only output that carries no recoverable content at all.
 */
internal object ContextCompactionPrompt {

    /**
     * Verbatim from the plan. Changing the wording changes what survives a fold, so edits belong in
     * the plan document first.
     */
    const val SYSTEM_PROMPT: String = """你是 ChatWithChat 的对话上下文压缩器。

请把给定的历史检查点和新增完整对话轮次，压缩成供后续模型继续工作的事实型上下文。

必须保留：
1. 用户明确提出的目标、偏好和限制。
2. 已确认的决定、方案、参数、文件路径、名称和数字。
3. 尚未解决的问题、待办事项和当前工作状态。
4. 关键工具结果、错误信息和外部引用。
5. 用户事实与助手推断之间的区别。

禁止：
1. 编造原文中不存在的信息。
2. 把助手猜测写成用户事实。
3. 删除会影响后续任务的约束、数字、代码或名称。
4. 添加新的建议、解释或行动。
5. 输出寒暄、元叙述或压缩过程说明。

只输出结构化上下文，不要输出代码围栏。"""

    /**
     * Appended to the system prompt so the model knows the exact shape expected back. Kept separate
     * from [SYSTEM_PROMPT] so the plan-mandated wording above stays byte-identical to the document.
     */
    private const val FORMAT_INSTRUCTION = """
输出格式（固定小节，缺少内容的小节写"无"，不要新增小节）：

## 目标与约束
## 已确认的决定
## 未解决的问题
## 关键结果与引用
## 事实与推断的区分"""

    val SECTION_HEADERS: List<String> = listOf(
        "## 目标与约束",
        "## 已确认的决定",
        "## 未解决的问题",
        "## 关键结果与引用",
        "## 事实与推断的区分"
    )

    private const val PREVIOUS_CHECKPOINT_HEADER = "# 历史检查点（此前已压缩的上下文）"
    private const val NEW_TURNS_HEADER = "# 新增完整对话轮次"
    private const val NO_PREVIOUS_CHECKPOINT = "（无，这是本次会话的第一次压缩）"

    /** Below this the model returned nothing worth storing, whatever it claims to be. */
    private const val MIN_USEFUL_BODY_LENGTH = 16

    /** Per-message floor, so a long batch cannot squeeze individual turns down to nothing. */
    private const val MIN_MESSAGE_TOKENS = 24

    fun systemPrompt(): String = SYSTEM_PROMPT + "\n" + FORMAT_INSTRUCTION

    /**
     * Builds the user payload: the previous checkpoint body followed by the turns being folded.
     *
     * The whole payload is capped at [maxTokens]. That cap is not cosmetic — the compaction call is
     * an ordinary request against the same model, so an uncapped payload would overflow the very
     * window the fold exists to stay inside. Every turn is represented rather than dropping the
     * oldest ones: coverage matters more than fidelity here, because a turn omitted from the payload
     * is a turn the checkpoint can never mention again.
     */
    fun buildUserPayload(
        previousCheckpointBody: String?,
        turns: List<ConversationTurn>,
        maxTokens: Int,
        tokenCounter: ContextTokenCounter
    ): String {
        val sections = mutableListOf<String>()
        sections += PREVIOUS_CHECKPOINT_HEADER
        sections += previousCheckpointBody?.takeIf { it.isNotBlank() } ?: NO_PREVIOUS_CHECKPOINT
        sections += ""
        sections += NEW_TURNS_HEADER

        val overhead = tokenCounter.estimateText(sections.joinToString("\n"))
        val messageCount: Int = turns.sumOf { turn -> if (turn.assistantMessage == null) 1 else 2 }
        val turnsBudget = (maxTokens - overhead).coerceAtLeast(MIN_MESSAGE_TOKENS * messageCount.coerceAtLeast(1))
        var remainingBudget = turnsBudget
        var remainingMessages = messageCount.coerceAtLeast(1)

        fun take(text: String): String {
            val perMessage = (remainingBudget / remainingMessages).coerceAtLeast(MIN_MESSAGE_TOKENS)
            val truncated = tokenCounter.truncateText(text, perMessage)
            remainingBudget = (remainingBudget - tokenCounter.estimateText(truncated)).coerceAtLeast(0)
            remainingMessages = (remainingMessages - 1).coerceAtLeast(1)
            return truncated
        }

        turns.forEachIndexed { index, turn ->
            sections += ""
            sections += "[轮次 ${index + 1}]"
            sections += "用户: ${take(turn.userMessage.content)}"
            turn.assistantMessage?.let { assistant ->
                sections += "助手: ${take(assistant.semanticAssistantContent())}"
            }
        }

        return sections.joinToString("\n").trim()
    }

    /**
     * Validates and normalises a compaction response.
     *
     * @return the checkpoint body, or null when the output cannot be trusted — in which case the
     *   caller falls back to [ConversationContextSummarizer] and writes nothing.
     */
    fun parse(rawOutput: String, maxTokens: Int, tokenCounter: ContextTokenCounter): String? {
        val stripped = stripCodeFences(rawOutput).trim()
        if (stripped.length < MIN_USEFUL_BODY_LENGTH) return null

        // A model that ignored the format entirely still produced prose about the conversation, and
        // prose is strictly better than the extractive fallback. Only content-free output is
        // rejected, so the section headers are a preference the prompt states, not a gate enforced
        // here — [hasExpectedSections] exists so callers can report format drift without losing the
        // body over it.
        return when {
            tokenCounter.estimateText(stripped) <= maxTokens -> stripped
            else -> tokenCounter.truncateText(stripped, maxTokens)
        }
    }

    /** Whether the model actually followed the requested section layout. Diagnostic only. */
    fun hasExpectedSections(body: String): Boolean = SECTION_HEADERS.any { body.contains(it) }

    /**
     * The prompt forbids code fences, but models add them anyway. Stripping is safe because the
     * checkpoint body is prose: a fence here is always packaging, never content.
     */
    private fun stripCodeFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed

        return trimmed
            .removePrefix("```")
            .substringAfter('\n', "")
            .substringBeforeLast("```")
            .trim()
    }
}
