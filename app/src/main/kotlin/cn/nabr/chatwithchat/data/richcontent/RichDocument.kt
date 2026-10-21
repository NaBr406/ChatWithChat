package cn.nabr.chatwithchat.data.richcontent

data class SourceRange(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0 && endExclusive >= start)
    }
}

data class RichDiagnostic(
    val code: String,
    val range: SourceRange,
    val detail: String? = null
)

sealed interface RichNode {
    val range: SourceRange

    data class Markdown(val text: String, override val range: SourceRange) : RichNode

    data class Component(
        val name: String,
        val attributes: Map<String, String>,
        val children: List<RichNode>,
        val raw: String,
        override val range: SourceRange
    ) : RichNode

    data class Invalid(val raw: String, val reason: String, override val range: SourceRange) : RichNode

    data class Incomplete(val raw: String, val name: String?, override val range: SourceRange) : RichNode
}

data class RichDocument(
    val source: String,
    val nodes: List<RichNode>,
    val diagnostics: List<RichDiagnostic>,
    val generation: Long = 0,
    val isStreaming: Boolean = false
)
