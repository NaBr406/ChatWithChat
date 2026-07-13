package dev.chungjungsoo.gptmobile.data.memory.embedding

import java.io.File
import java.io.InputStream

class BertWordPieceTokenizer internal constructor(
    private val tokenIds: Map<String, Int>,
    val maxInputTokens: Int = DEFAULT_MAX_INPUT_TOKENS
) {
    init {
        require(maxInputTokens in MIN_INPUT_TOKENS..MAX_SUPPORTED_INPUT_TOKENS) {
            "maxInputTokens must be between $MIN_INPUT_TOKENS and $MAX_SUPPORTED_INPUT_TOKENS"
        }
        SPECIAL_TOKEN_IDS.forEach { (token, expectedId) ->
            require(tokenIds[token] == expectedId) {
                "The fixed BERT vocabulary must map $token to $expectedId"
            }
        }
    }

    fun encode(
        text: String,
        maxTokens: Int = maxInputTokens,
        padToMaxTokens: Boolean = false
    ): BertTokenizedInput {
        require(maxTokens in MIN_INPUT_TOKENS..maxInputTokens) {
            "maxTokens must be between $MIN_INPUT_TOKENS and $maxInputTokens"
        }

        val contentTokens = tokenize(text).take(maxTokens - SPECIAL_TOKENS_PER_INPUT)
        val unpaddedSize = contentTokens.size + SPECIAL_TOKENS_PER_INPUT
        val outputSize = if (padToMaxTokens) maxTokens else unpaddedSize
        val inputIds = LongArray(outputSize) { PAD_TOKEN_ID.toLong() }
        val attentionMask = LongArray(outputSize)
        val tokenTypeIds = LongArray(outputSize)

        inputIds[0] = CLS_TOKEN_ID.toLong()
        contentTokens.forEachIndexed { index, token ->
            inputIds[index + 1] = tokenIds.getValue(token).toLong()
        }
        inputIds[unpaddedSize - 1] = SEP_TOKEN_ID.toLong()
        attentionMask.fill(1L, toIndex = unpaddedSize)

        return BertTokenizedInput(
            inputIds = inputIds,
            attentionMask = attentionMask,
            tokenTypeIds = tokenTypeIds
        )
    }

    internal fun tokenize(text: String): List<String> {
        val basicTokens = basicTokenizePreservingSpecialTokens(text)
        return buildList {
            basicTokens.forEach { token ->
                if (token in SPECIAL_TOKENS) {
                    add(token)
                } else {
                    addAll(wordPieceTokenize(token))
                }
            }
        }
    }

    private fun basicTokenizePreservingSpecialTokens(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val output = mutableListOf<String>()
        var ordinaryStart = 0
        var index = 0
        while (index < text.length) {
            val specialToken = SPECIAL_TOKENS.firstOrNull { token -> text.startsWith(token, index) }
            if (specialToken == null) {
                index += Character.charCount(text.codePointAt(index))
                continue
            }

            if (ordinaryStart < index) {
                output += basicTokenize(text.substring(ordinaryStart, index))
            }
            output += specialToken
            index += specialToken.length
            ordinaryStart = index
        }

        if (ordinaryStart < text.length) {
            output += basicTokenize(text.substring(ordinaryStart))
        }
        return output
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        text.forEachCodePoint { codePoint ->
            when {
                codePoint == 0 || codePoint == REPLACEMENT_CHARACTER || isControl(codePoint) -> Unit
                isWhitespace(codePoint) -> cleaned.append(' ')
                isChineseCharacter(codePoint) -> {
                    cleaned.append(' ')
                    cleaned.appendCodePoint(codePoint)
                    cleaned.append(' ')
                }
                else -> cleaned.appendCodePoint(codePoint)
            }
        }

        return buildList {
            whitespaceTokenize(cleaned.toString()).forEach { token ->
                addAll(splitOnPunctuation(token))
            }
        }
    }

    private fun wordPieceTokenize(token: String): List<String> {
        val codePoints = token.toCodePointList()
        if (codePoints.size > MAX_INPUT_CHARACTERS_PER_WORD) return listOf(UNK_TOKEN)

        val pieces = mutableListOf<String>()
        var start = 0
        while (start < codePoints.size) {
            var end = codePoints.size
            var matchedPiece: String? = null
            while (start < end) {
                val candidate = buildString {
                    if (start > 0) append(CONTINUATION_PREFIX)
                    for (index in start until end) appendCodePoint(codePoints[index])
                }
                if (candidate in tokenIds) {
                    matchedPiece = candidate
                    break
                }
                end -= 1
            }
            if (matchedPiece == null) return listOf(UNK_TOKEN)
            pieces += matchedPiece
            start = end
        }
        return pieces
    }

    private fun splitOnPunctuation(token: String): List<String> = buildList {
        val current = StringBuilder()
        token.forEachCodePoint { codePoint ->
            if (isPunctuation(codePoint)) {
                if (current.isNotEmpty()) {
                    add(current.toString())
                    current.setLength(0)
                }
                add(String(Character.toChars(codePoint)))
            } else {
                current.appendCodePoint(codePoint)
            }
        }
        if (current.isNotEmpty()) add(current.toString())
    }

    private fun whitespaceTokenize(text: String): List<String> = buildList {
        val current = StringBuilder()
        text.forEachCodePoint { codePoint ->
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (current.isNotEmpty()) {
                    add(current.toString())
                    current.setLength(0)
                }
            } else {
                current.appendCodePoint(codePoint)
            }
        }
        if (current.isNotEmpty()) add(current.toString())
    }

    private fun isWhitespace(codePoint: Int): Boolean =
        codePoint == ' '.code ||
            codePoint == '\t'.code ||
            codePoint == '\n'.code ||
            codePoint == '\r'.code ||
            Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(codePoint: Int): Boolean {
        if (codePoint == '\t'.code || codePoint == '\n'.code || codePoint == '\r'.code) return false
        return when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt() -> true
            else -> false
        }
    }

    private fun isPunctuation(codePoint: Int): Boolean {
        if (codePoint in 33..47 || codePoint in 58..64 || codePoint in 91..96 || codePoint in 123..126) {
            return true
        }
        return when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isChineseCharacter(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF ||
            codePoint in 0x3400..0x4DBF ||
            codePoint in 0x20000..0x2A6DF ||
            codePoint in 0x2A700..0x2B73F ||
            codePoint in 0x2B740..0x2B81F ||
            codePoint in 0x2B820..0x2CEAF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x2F800..0x2FA1F

    private inline fun String.forEachCodePoint(block: (Int) -> Unit) {
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            block(codePoint)
            index += Character.charCount(codePoint)
        }
    }

    private fun String.toCodePointList(): List<Int> = buildList {
        forEachCodePoint(::add)
    }

    companion object {
        const val DEFAULT_MAX_INPUT_TOKENS = 256
        const val MAX_SUPPORTED_INPUT_TOKENS = 256

        fun fromVocab(
            input: InputStream,
            maxInputTokens: Int = DEFAULT_MAX_INPUT_TOKENS
        ): BertWordPieceTokenizer {
            val tokens = input.bufferedReader(Charsets.UTF_8).use { reader -> reader.readLines() }
            require(tokens.isNotEmpty()) { "vocab.txt must not be empty" }
            val tokenIds = LinkedHashMap<String, Int>(tokens.size)
            tokens.forEachIndexed { index, token ->
                require(tokenIds.put(token, index) == null) { "vocab.txt contains duplicate token: $token" }
            }
            return BertWordPieceTokenizer(tokenIds, maxInputTokens)
        }

        fun fromVocabFile(
            file: File,
            maxInputTokens: Int = DEFAULT_MAX_INPUT_TOKENS
        ): BertWordPieceTokenizer = file.inputStream().use { input ->
            fromVocab(input, maxInputTokens)
        }

        private const val MIN_INPUT_TOKENS = 2
        private const val SPECIAL_TOKENS_PER_INPUT = 2
        private const val MAX_INPUT_CHARACTERS_PER_WORD = 100
        private const val REPLACEMENT_CHARACTER = 0xFFFD
        private const val CONTINUATION_PREFIX = "##"
        private const val PAD_TOKEN = "[PAD]"
        private const val UNK_TOKEN = "[UNK]"
        private const val CLS_TOKEN = "[CLS]"
        private const val SEP_TOKEN = "[SEP]"
        private const val MASK_TOKEN = "[MASK]"
        private const val PAD_TOKEN_ID = 0
        private const val UNK_TOKEN_ID = 100
        private const val CLS_TOKEN_ID = 101
        private const val SEP_TOKEN_ID = 102
        private const val MASK_TOKEN_ID = 103
        private val SPECIAL_TOKEN_IDS = linkedMapOf(
            PAD_TOKEN to PAD_TOKEN_ID,
            UNK_TOKEN to UNK_TOKEN_ID,
            CLS_TOKEN to CLS_TOKEN_ID,
            SEP_TOKEN to SEP_TOKEN_ID,
            MASK_TOKEN to MASK_TOKEN_ID
        )
        private val SPECIAL_TOKENS = SPECIAL_TOKEN_IDS.keys
    }
}

data class BertTokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
) {
    init {
        require(inputIds.size == attentionMask.size && inputIds.size == tokenTypeIds.size) {
            "BERT input arrays must have the same length"
        }
    }
}
