package cn.nabr.chatwithchat.data.title

import cn.nabr.chatwithchat.data.ModelConstants

/**
 * Builds the payload for the title call and cleans up what comes back.
 *
 * Kept apart from [ChatTitleGenerationService] so both halves can be tested without a model: the
 * parser is where nearly all the real-world failure modes live — models that answer with a Markdown
 * heading, wrap the title in quotes, prepend "标题：", or emit a `<think>` block ahead of the answer.
 */
internal object ChatTitlePrompt {

    const val MAX_TITLE_LENGTH = 50

    /**
     * A short system prompt is still sent even though [ModelConstants.CHAT_TITLE_GENERATE_PROMPT]
     * already carries the instructions: Anthropic and Google take the system prompt as a separate
     * field, and leaving it empty there loses the one hint that keeps chatty models from answering
     * the conversation instead of naming it.
     */
    const val SYSTEM_PROMPT = "你是会话标题生成器。只输出标题本身，不要解释，不要加引号。"

    private const val MAX_TURNS = 2
    private const val MAX_MESSAGE_LENGTH = 500
    private const val MAX_TRANSCRIPT_LENGTH = 2_000

    private val WHITESPACE = Regex("\\s+")
    private val THINK_BLOCK = Regex("(?is)<(think|thinking)>.*?</\\1>")
    private val UNCLOSED_THINK_BLOCK = Regex("(?is)<(think|thinking)>.*")
    private val LEADING_LABEL = Regex("^(标题|title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    private val LEADING_BULLET = Regex("^[-*•>#\\s]+")
    private val SURROUNDING_QUOTES = Regex("^[\"'“”‘’「」『』《》]+|[\"'“”‘’「」『』《》]+$")

    /**
     * [ModelConstants.CHAT_TITLE_GENERATE_PROMPT] ends with a `[聊天内容]` marker, so it is used
     * verbatim as the prefix of the payload rather than being re-worded here — wiring up that exact
     * constant is the point of this path.
     */
    fun buildUserPayload(turns: List<ChatTitleTurn>): String? {
        val transcript = turns
            .take(MAX_TURNS)
            .flatMap { turn ->
                listOfNotNull(
                    turn.userMessage.condense()?.let { "用户: $it" },
                    turn.assistantMessage?.condense()?.let { "助手: $it" }
                )
            }
            .joinToString("\n")
            .take(MAX_TRANSCRIPT_LENGTH)

        return transcript.takeIf { it.isNotBlank() }?.let { ModelConstants.CHAT_TITLE_GENERATE_PROMPT + it }
    }

    /**
     * Returns a usable title, or `null` when the model gave back nothing that could serve as one. A
     * `null` is not an error — the caller keeps the truncated first message it already has.
     */
    fun parse(rawOutput: String): String? {
        val withoutThoughts = rawOutput
            .replace(THINK_BLOCK, " ")
            .replace(UNCLOSED_THINK_BLOCK, " ")

        val firstMeaningfulLine = withoutThoughts
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        val cleaned = firstMeaningfulLine
            .replace(LEADING_BULLET, "")
            .replace(LEADING_LABEL, "")
            .replace("**", "")
            .replace(WHITESPACE, " ")
            .replace(SURROUNDING_QUOTES, "")
            .trim()

        return cleaned.takeIf { it.isNotBlank() }?.take(MAX_TITLE_LENGTH)
    }

    private fun String.condense(): String? = replace(WHITESPACE, " ")
        .trim()
        .takeIf { it.isNotBlank() }
        ?.take(MAX_MESSAGE_LENGTH)
}

/** One exchange, already reduced to plain text by the caller. */
data class ChatTitleTurn(
    val userMessage: String,
    val assistantMessage: String?
)
