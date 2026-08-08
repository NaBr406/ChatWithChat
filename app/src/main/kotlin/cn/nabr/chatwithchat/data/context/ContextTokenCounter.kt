package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType

/**
 * Real tokenizer-backed counter (JTokkit), with a CJK-aware heuristic fallback when no encoding can
 * be resolved for the model.
 *
 * Extracted verbatim from [ContextBuilder] so the budget model, the compaction services, the native
 * `compact_threshold` calculation and the budget warnings all count tokens the same way. Behaviour
 * and constants are unchanged from the original private implementation.
 */
internal class ContextTokenCounter private constructor(
    private val encoding: Encoding?
) {
    fun estimateTurns(turns: List<ConversationTurn>): Int = turns.sumOf(::estimateTurn)

    fun estimateTurn(turn: ConversationTurn): Int =
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

    fun estimateMessage(message: MessageV2): Int =
        MESSAGE_OVERHEAD_TOKENS +
            estimateText(message.semanticAssistantContent()) +
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
