package cn.nabr.chatwithchat.data.richcontent

data class RichContentUpdate(
    val document: RichDocument,
    val reset: Boolean,
    val resetReason: ResetReason?
)

enum class ResetReason { INITIAL, GENERATION_CHANGED, TEXT_SHORTENED, PREFIX_REPLACED }

class RichContentAccumulator(private val parser: RichContentParser = RichContentParser()) {
    private var previousText: String? = null
    private var currentGeneration: Long? = null

    fun update(fullText: String, isStreaming: Boolean, generation: Long): RichContentUpdate {
        val old = previousText
        val oldGeneration = currentGeneration
        val reason = when {
            old == null -> ResetReason.INITIAL
            oldGeneration != generation -> ResetReason.GENERATION_CHANGED
            fullText.length < old.length -> ResetReason.TEXT_SHORTENED
            !fullText.startsWith(old) -> ResetReason.PREFIX_REPLACED
            else -> null
        }
        previousText = fullText
        currentGeneration = generation
        return RichContentUpdate(
            document = parser.parse(fullText, isStreaming, generation),
            reset = reason != null,
            resetReason = reason
        )
    }

    fun append(delta: String, isStreaming: Boolean = true, generation: Long = currentGeneration ?: 0): RichContentUpdate {
        return update((previousText ?: "") + delta, isStreaming, generation)
    }

    fun reset() {
        previousText = null
        currentGeneration = null
    }
}
