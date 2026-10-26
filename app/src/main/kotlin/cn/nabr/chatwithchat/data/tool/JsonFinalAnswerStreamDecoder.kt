package cn.nabr.chatwithchat.data.tool

/**
 * Previews only the canonical final-answer envelope advertised by [ToolPromptBuilder].
 * Other field orders and formats remain buffered for the full protocol parser. This is not a
 * replacement for that parser: callers must validate the complete envelope before committing a preview.
 */
internal class JsonFinalAnswerStreamDecoder {
    private val prefix = StringBuilder()
    private val decoded = StringBuilder()
    private val unicode = StringBuilder()
    private var isReadingContent = false
    private var isFinished = false
    private var isEscaped = false
    private var isReadingUnicode = false
    private var pendingHighSurrogate: Char? = null

    val content: String
        get() = decoded.toString()

    fun append(fragment: String): String {
        val previousLength = decoded.length
        fragment.forEach { char ->
            when {
                isFinished -> Unit
                !isReadingContent -> {
                    prefix.append(char)
                    if (CONTENT_PREFIX.matches(prefix)) {
                        isReadingContent = true
                        prefix.clear()
                    } else if (prefix.length > MAX_PREFIX_CHARS) {
                        isFinished = true
                    }
                }
                isReadingUnicode -> {
                    if (char.digitToIntOrNull(16) == null) {
                        isFinished = true
                    } else {
                        unicode.append(char)
                        if (unicode.length == 4) {
                            appendDecoded(unicode.toString().toInt(16).toChar())
                            unicode.clear()
                            isReadingUnicode = false
                        }
                    }
                }
                isEscaped -> {
                    isEscaped = false
                    when (char) {
                        'u' -> isReadingUnicode = true
                        '"', '\\', '/' -> appendDecoded(char)
                        'b' -> appendDecoded('\b')
                        'f' -> appendDecoded('\u000C')
                        'n' -> appendDecoded('\n')
                        'r' -> appendDecoded('\r')
                        't' -> appendDecoded('\t')
                        else -> isFinished = true
                    }
                }
                char == '\\' -> isEscaped = true
                char == '"' || char < ' ' -> isFinished = true
                else -> appendDecoded(char)
            }
        }
        return decoded.substring(previousLength)
    }

    private fun appendDecoded(char: Char) {
        val high = pendingHighSurrogate
        when {
            high != null && char.isLowSurrogate() -> {
                decoded.append(high).append(char)
                pendingHighSurrogate = null
            }
            high != null || char.isLowSurrogate() -> isFinished = true
            char.isHighSurrogate() -> pendingHighSurrogate = char
            else -> decoded.append(char)
        }
    }

    private companion object {
        const val MAX_PREFIX_CHARS = 256
        val CONTENT_PREFIX = Regex("""[ \t\r\n]*\{[ \t\r\n]*"type"[ \t\r\n]*:[ \t\r\n]*"final_answer"[ \t\r\n]*,[ \t\r\n]*"content"[ \t\r\n]*:[ \t\r\n]*["]""")
    }
}
