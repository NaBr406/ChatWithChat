package cn.nabr.chatwithchat.data.richcontent.openui

sealed interface OpenUiPart {
    data class Markdown(val text: String) : OpenUiPart
    data class Program(val source: String, val isComplete: Boolean) : OpenUiPart
}

data class OpenUiContent(val parts: List<OpenUiPart>) {
    val markdown: String get() = parts.filterIsInstance<OpenUiPart.Markdown>().joinToString("") { it.text }
    val source: String? get() = parts.filterIsInstance<OpenUiPart.Program>().firstOrNull()?.source
    val isComplete: Boolean get() = parts.filterIsInstance<OpenUiPart.Program>().all { it.isComplete }
}

/** Explicit fenced envelope: normal code fences and historical XML are never guessed as OpenUI. */
object OpenUiEnvelope {
    fun containsOpenUi(content: String): Boolean = parse(content).parts.any { it is OpenUiPart.Program }

    fun toMarkdown(content: String): String {
        val envelope = parse(content)
        return envelope.markdown.trim().ifBlank {
            if (envelope.source != null) "[Interactive answer: no accompanying explanation was provided.]" else ""
        }
    }

    fun parse(content: String, isStreaming: Boolean = false): OpenUiContent {
        val parts = mutableListOf<OpenUiPart>()
        var ordinaryFence: Pair<Char, Int>? = null
        var programFence: Pair<Char, Int>? = null
        var markdownStart = 0
        var sourceStart = 0
        var offset = 0
        for (line in content.splitToSequence('\n')) {
            val lineEnd = (offset + line.length + 1).coerceAtMost(content.length)
            val match = fence.matchEntire(line.removeSuffix("\r"))
            val marker = match?.groupValues?.get(1)
            val info = match?.groupValues?.get(2)?.trim().orEmpty()
            if (marker != null) {
                val token = marker.first() to marker.length
                val currentProgram = programFence
                val currentOrdinary = ordinaryFence
                when {
                    currentProgram != null && token.first == currentProgram.first && token.second >= currentProgram.second && info.isEmpty() -> {
                        parts += OpenUiPart.Program(content.substring(sourceStart, offset).trimEnd('\r', '\n'), true)
                        programFence = null
                        markdownStart = lineEnd
                    }
                    currentProgram != null -> Unit
                    currentOrdinary != null && token.first == currentOrdinary.first && token.second >= currentOrdinary.second && info.isEmpty() -> ordinaryFence = null
                    currentOrdinary != null -> Unit
                    info == "openui" -> {
                        if (offset > markdownStart) parts += OpenUiPart.Markdown(content.substring(markdownStart, offset))
                        programFence = token
                        sourceStart = lineEnd
                    }
                    else -> ordinaryFence = token
                }
            }
            offset = lineEnd
        }
        if (programFence != null) {
            parts += OpenUiPart.Program(content.substring(sourceStart), false)
        } else if (markdownStart < content.length) {
            var markdown = content.substring(markdownStart)
            // Do not flash an opening protocol marker while its label is arriving.
            if (isStreaming && ordinaryFence != null) {
                val lastLine = markdown.substringAfterLast('\n')
                val trimmed = lastLine.trimStart()
                if (trimmed.startsWith("```") && "```openui".startsWith(trimmed)) markdown = markdown.dropLast(lastLine.length)
            }
            if (markdown.isNotEmpty()) parts += OpenUiPart.Markdown(markdown)
        }
        return OpenUiContent(parts)
    }

    private val fence = Regex(" {0,3}(`{3,}|~{3,})([^`~]*)")
}
