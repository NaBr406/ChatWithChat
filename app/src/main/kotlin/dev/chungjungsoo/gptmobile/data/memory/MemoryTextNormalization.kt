package dev.chungjungsoo.gptmobile.data.memory

internal fun normalizeExactMemoryText(text: String): String = buildString(text.length) {
    var hasText = false
    var pendingSpace = false
    text.lowercase().forEachCodePoint { codePoint ->
        if (codePoint.isUnicodeMemoryWhitespace()) {
            pendingSpace = hasText
        } else {
            if (pendingSpace) append(' ')
            appendCodePoint(codePoint)
            hasText = true
            pendingSpace = false
        }
    }
}

private fun Int.isUnicodeMemoryWhitespace(): Boolean =
    this in 0x0009..0x000D ||
        this == 0x0020 ||
        this == 0x0085 ||
        this == 0x00A0 ||
        this == 0x1680 ||
        this in 0x2000..0x200A ||
        this in 0x2028..0x2029 ||
        this == 0x202F ||
        this == 0x205F ||
        this == 0x3000

private inline fun String.forEachCodePoint(block: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        block(codePoint)
        index += Character.charCount(codePoint)
    }
}
