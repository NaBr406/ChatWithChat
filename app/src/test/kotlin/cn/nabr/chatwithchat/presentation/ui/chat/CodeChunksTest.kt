package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeChunksTest {
    @Test
    fun chunkRanges_stackBackIntoTheSameLines() {
        listOf("", "one line", "a\nb", "a\nb\n", "1\n2\n3\n4\n5\n6\n7", "1\n2\n3\n4\n5\n6\n").forEach { code ->
            val ranges = codeChunkRanges(code, linesPerChunk = 3)
            assertEquals(code, ranges.joinToString("\n") { code.substring(it.first, it.last + 1) })
            ranges.dropLast(1).forEach { range -> assertEquals(2, code.substring(range.first, range.last + 1).count { it == '\n' }) }
        }
    }

    @Test
    fun chunkRanges_shortBlockIsOnePiece() {
        val code = (1..CODE_CHUNK_LINES).joinToString("\n") { "line $it" }
        assertEquals(listOf(code.indices), codeChunkRanges(code))
        assertEquals(2, codeChunkRanges(code + "\nnext").size)
    }

    @Test
    fun highlightCoverage_onlyCountsAHighlightOfAPrefix() {
        assertEquals(0, highlightedCodeCoverage("val a", null))
        assertEquals(3, highlightedCodeCoverage("val a", AnnotatedString("val")))
        assertEquals(5, highlightedCodeCoverage("val a", AnnotatedString("val a")))
        assertEquals(0, highlightedCodeCoverage("var a", AnnotatedString("val")))
        assertEquals(0, highlightedCodeCoverage("val", AnnotatedString("val a")))
    }
}
