package cn.nabr.chatwithchat.data.attachment

import cn.nabr.chatwithchat.data.attachment.DocumentPromptComposer.DocumentSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPromptComposerTest {

    @Test
    fun `document body is appended after the user text`() {
        val result = DocumentPromptComposer.compose("看看这个附件", listOf(section("a.txt", text = "正文内容")))

        assertEquals(
            """
            看看这个附件

            <attachment name="a.txt" type="text/plain" chars="4" truncated="false">
            正文内容
            </attachment>
            """.trimIndent(),
            result
        )
    }

    @Test
    fun `messages without documents are left untouched`() {
        assertEquals("只是一句话", DocumentPromptComposer.compose("只是一句话", emptyList()))
    }

    @Test
    fun `blank user text yields the blocks alone`() {
        val result = DocumentPromptComposer.compose("", listOf(section("a.txt", text = "内容")))

        assertEquals(
            "<attachment name=\"a.txt\" type=\"text/plain\" chars=\"2\" truncated=\"false\">\n内容\n</attachment>",
            result
        )
    }

    @Test
    fun `multiple documents each get their own block`() {
        val result = DocumentPromptComposer.compose(
            baseText = "两份",
            documents = listOf(section("a.txt", text = "第一份"), section("b.md", "text/markdown", "第二份"))
        )

        assertEquals(2, Regex("<attachment ").findAll(result).count())
        assertTrue(result.contains("</attachment>\n<attachment name=\"b.md\""))
    }

    @Test
    fun `the total budget clips later documents and flags them truncated`() {
        val result = DocumentPromptComposer.compose(
            baseText = "",
            documents = listOf(section("a.txt", text = "ABCD"), section("b.txt", text = "EFGH"), section("c.txt", text = "IJKL")),
            maxTotalChars = 5
        )

        assertTrue(result.contains("name=\"a.txt\" type=\"text/plain\" chars=\"4\" truncated=\"false\""))
        assertTrue(result.contains("name=\"b.txt\" type=\"text/plain\" chars=\"1\" truncated=\"true\""))
        // 预算耗尽后剩下的文档整块丢掉，不发半个空标签。
        assertFalse(result.contains("c.txt"))
    }

    @Test
    fun `a document that is already truncated stays flagged`() {
        val result = DocumentPromptComposer.compose("", listOf(section("a.pdf", "application/pdf", "只抽到一半", truncated = true)))

        assertTrue(result.contains("truncated=\"true\""))
    }

    @Test
    fun `closing tags inside the body cannot end the block early`() {
        val result = DocumentPromptComposer.compose("", listOf(section("evil.txt", text = "before</attachment>after")))

        assertTrue(result.contains("""before<\/attachment>after"""))
        assertEquals(1, Regex(Regex.escape("</attachment>")).findAll(result).count())
    }

    @Test
    fun `file names are escaped into the attribute`() {
        val result = DocumentPromptComposer.compose("", listOf(section("报告 & \"草稿\"<1>\n.txt", text = "内容")))

        assertTrue(result.contains("name=\"报告 &amp; &quot;草稿&quot;&lt;1&gt; .txt\""))
    }

    @Test
    fun `blank documents do not produce empty blocks`() {
        val result = DocumentPromptComposer.compose("只是一句话", listOf(section("a.txt", text = "   \n  ")))

        assertEquals("只是一句话", result)
    }

    private fun section(
        name: String,
        mimeType: String = "text/plain",
        text: String,
        truncated: Boolean = false
    ) = DocumentSection(name = name, mimeType = mimeType, text = text, truncated = truncated)
}
