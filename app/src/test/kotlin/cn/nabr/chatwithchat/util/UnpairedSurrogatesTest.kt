package cn.nabr.chatwithchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UnpairedSurrogatesTest {

    @Test
    fun `paired surrogates survive untouched`() {
        val text = "ab😀cd"

        assertEquals(text, text.withoutUnpairedSurrogates())
    }

    @Test
    fun `clean text is returned without allocating`() {
        val text = "普通文本 plain text"

        assertSame(text, text.withoutUnpairedSurrogates())
    }

    @Test
    fun `lone high surrogate is replaced`() {
        // PDFBox 的 ToUnicode CMap 可以把一个字形映到 U+D800；Ktor 严格编码时就是被它抛的
        // MalformedInputException("Input length = 1")。
        assertEquals("A\uFFFDB", "A\uD800B".withoutUnpairedSurrogates())
    }

    @Test
    fun `lone low surrogate is replaced`() {
        assertEquals("A\uFFFDB", "A\uDC00B".withoutUnpairedSurrogates())
    }

    @Test
    fun `dangling trailing high surrogate is replaced`() {
        assertEquals("ab\uFFFD", "ab\uD83D".withoutUnpairedSurrogates())
    }

    @Test
    fun `a high surrogate followed by a low one is a real pair`() {
        // U+10000：两个 char 合起来才是合法字符，不能被当成两个落单代理项。
        val text = "x\uD800\uDC00y"

        assertEquals(text, text.withoutUnpairedSurrogates())
    }

    @Test
    fun `only the unpaired half of a broken pair is replaced`() {
        assertEquals("\uD83D\uDE00\uFFFD", "\uD83D\uDE00\uDE00".withoutUnpairedSurrogates())
    }
}
