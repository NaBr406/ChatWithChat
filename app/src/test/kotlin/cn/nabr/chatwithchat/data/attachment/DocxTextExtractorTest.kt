package cn.nabr.chatwithchat.data.attachment

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocxTextExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `paragraphs are separated by newlines`() {
        val result = DocxTextExtractor.parse(documentXml("<w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p><w:r><w:t>第二段</w:t></w:r></w:p>"), MAX_CHARS)

        assertNotNull(result)
        assertEquals("第一段\n第二段", result?.text)
        assertFalse(result!!.truncated)
    }

    @Test
    fun `tab and break elements become whitespace`() {
        val result = DocxTextExtractor.parse(
            documentXml("<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p>"),
            MAX_CHARS
        )

        assertEquals("A\tB\nC", result?.text)
    }

    @Test
    fun `table cells and rows keep their grid shape`() {
        val result = DocxTextExtractor.parse(
            documentXml(
                "<w:tbl>" +
                    "<w:tr><w:tc><w:p><w:r><w:t>a1</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>b1</w:t></w:r></w:p></w:tc></w:tr>" +
                    "<w:tr><w:tc><w:p><w:r><w:t>a2</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>b2</w:t></w:r></w:p></w:tc></w:tr>" +
                    "</w:tbl>"
            ),
            MAX_CHARS
        )

        // 单元格用 \t 分隔、行用 \n 分隔；单元格内段落的换行不能升级成行分隔，否则行结构就散了。
        assertEquals("a1\tb1\na2\tb2", result?.text)
    }

    @Test
    fun `multiple paragraphs inside one cell stay on the same row`() {
        val result = DocxTextExtractor.parse(
            documentXml(
                "<w:tbl><w:tr>" +
                    "<w:tc><w:p><w:r><w:t>上</w:t></w:r></w:p><w:p><w:r><w:t>下</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>右</w:t></w:r></w:p></w:tc>" +
                    "</w:tr></w:tbl>"
            ),
            MAX_CHARS
        )

        assertEquals("上 下\t右", result?.text)
    }

    @Test
    fun `leading tab indentation survives outside tables`() {
        val result = DocxTextExtractor.parse(
            documentXml("<w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p><w:r><w:tab/><w:t>缩进段</w:t></w:r></w:p>"),
            MAX_CHARS
        )

        assertEquals("第一段\n\t缩进段", result?.text)
    }

    @Test
    fun `field codes and deleted revisions are skipped`() {
        val fieldCode = DocxTextExtractor.parse(
            documentXml(
                "<w:p><w:r><w:instrText>HYPERLINK \"http://x\"<w:t>藏起来</w:t></w:instrText>" +
                    "<w:t>可见</w:t></w:r></w:p>"
            ),
            MAX_CHARS
        )
        val deleted = DocxTextExtractor.parse(
            documentXml("<w:p><w:r><w:delText>删掉的</w:delText></w:r><w:r><w:t>保留</w:t></w:r></w:p>"),
            MAX_CHARS
        )

        assertEquals("可见", fieldCode?.text)
        assertEquals("保留", deleted?.text)
    }

    @Test
    fun `doctype declarations are rejected`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE w:document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <w:document><w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertNull(DocxTextExtractor.parse(xml, MAX_CHARS))
    }

    @Test
    fun `hitting the character cap aborts parsing and flags truncation`() {
        val result = DocxTextExtractor.parse(
            documentXml("<w:p><w:r><w:t>ABCDEFGH</w:t></w:r></w:p><w:p><w:r><w:t>忽略</w:t></w:r></w:p>"),
            maxChars = 4
        )

        assertEquals("ABCD", result?.text)
        assertEquals(4, result?.charCount)
        assertTrue(result!!.truncated)
    }

    @Test
    fun `document without any text is not a document`() {
        assertNull(DocxTextExtractor.parse(documentXml("<w:p/>"), MAX_CHARS))
    }

    @Test
    fun `only the main document part is read out of the container`() {
        val docx = writeDocx(
            "word/styles.xml" to documentXml("<w:p><w:r><w:t>样式表</w:t></w:r></w:p>"),
            "word/document.xml" to documentXml("<w:p><w:r><w:t>正文</w:t></w:r></w:p>"),
            "word/footer1.xml" to documentXml("<w:p><w:r><w:t>页脚</w:t></w:r></w:p>")
        )

        assertEquals("正文", DocxTextExtractor.extract(docx, MAX_CHARS)?.text)
    }

    @Test
    fun `container without a document part yields nothing`() {
        val docx = writeDocx("word/styles.xml" to documentXml("<w:p><w:r><w:t>样式表</w:t></w:r></w:p>"))

        assertNull(DocxTextExtractor.extract(docx, MAX_CHARS))
    }

    private fun documentXml(body: String): ByteArray =
        """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
            .toByteArray(StandardCharsets.UTF_8)

    private fun writeDocx(vararg entries: Pair<String, ByteArray>): File {
        val file = temporaryFolder.newFile("sample-${entries.size}-${entries.first().first.hashCode()}.docx")
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private companion object {
        const val MAX_CHARS = 10_000
    }
}
