package cn.nabr.chatwithchat.data.attachment

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTextExtractorTest {

    @Test
    fun `bom marked bytes decode with the declared charset`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray(StandardCharsets.UTF_8)
        val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(StandardCharsets.UTF_16LE)
        val utf16Be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hi".toByteArray(StandardCharsets.UTF_16BE)

        assertEquals("你好", DocumentTextExtractor.decodeTextBytes(utf8))
        // UTF-16 的 ASCII 字符自带 NUL 字节，必须先认 BOM 才不会被判成二进制。
        assertEquals("hi", DocumentTextExtractor.decodeTextBytes(utf16Le))
        assertEquals("hi", DocumentTextExtractor.decodeTextBytes(utf16Be))
    }

    @Test
    fun `bom free utf8 decodes strictly`() {
        val bytes = "# 标题\nplain text".toByteArray(StandardCharsets.UTF_8)

        assertEquals("# 标题\nplain text", DocumentTextExtractor.decodeTextBytes(bytes))
    }

    @Test
    fun `invalid utf8 falls back to gbk`() {
        val bytes = "中文测试".toByteArray(Charset.forName("GBK"))

        // 这串字节不是合法 UTF-8，严格解码必须失败后才轮到 GBK。
        assertEquals("中文测试", DocumentTextExtractor.decodeTextBytes(bytes))
    }

    @Test
    fun `bytes containing nul are rejected as binary`() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x14, 0x00, 0x08)

        assertNull(DocumentTextExtractor.decodeTextBytes(bytes))
    }

    @Test
    fun `empty bytes are not text`() {
        assertNull(DocumentTextExtractor.decodeTextBytes(ByteArray(0)))
    }

    @Test
    fun `truncation normalizes line endings without flagging short text`() {
        val result = DocumentTextExtractor.truncateDocumentText("a\r\nb\rc", maxChars = 100)

        assertEquals("a\nb\nc", result.text)
        assertEquals(5, result.charCount)
        assertFalse(result.truncated)
    }

    @Test
    fun `text over the cap is clipped and flagged`() {
        val result = DocumentTextExtractor.truncateDocumentText("abcdef", maxChars = 3)

        assertEquals("abc", result.text)
        assertEquals(3, result.charCount)
        assertTrue(result.truncated)
    }

    @Test
    fun `clipping never splits a surrogate pair`() {
        // "ab" + 一个 emoji（两个 char），在第 3 个 char 处截断会切开代理对。
        val result = DocumentTextExtractor.truncateDocumentText("ab😀", maxChars = 3)

        assertEquals("ab", result.text)
        assertEquals(2, result.charCount)
        assertTrue(result.truncated)
    }

    @Test
    fun `truncation replaces unpaired surrogates`() {
        // PDFBox 的 ToUnicode CMap 可以把一个字形映到 U+D800——这种 char 谁也编码不出去。
        val result = DocumentTextExtractor.truncateDocumentText("ab\uD800cd", maxChars = 100)

        assertEquals("ab\uFFFDcd", result.text)
        assertEquals(5, result.charCount)
        assertFalse(result.truncated)
    }

    @Test
    fun `extracted text survives the strict utf8 encode used for request bodies`() {
        val raw = "A\uD800B"
        val extracted = DocumentTextExtractor.truncateDocumentText(raw, maxChars = 100).text

        // Ktor 给 String 请求体用的就是这一档严格编码：抽取结果必须过得了，否则整条消息在本地发不出去。
        assertTrue(strictUtf8Encode(raw).isFailure)
        assertTrue(strictUtf8Encode(extracted).isSuccess)
    }

    private fun strictUtf8Encode(text: String): Result<Int> = runCatching {
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
            .remaining()
    }
}
