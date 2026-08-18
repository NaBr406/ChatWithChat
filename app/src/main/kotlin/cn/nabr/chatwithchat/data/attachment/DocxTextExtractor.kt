package cn.nabr.chatwithchat.data.attachment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * 从 docx（OOXML zip）里取出正文。只解 `word/document.xml`：页眉页脚、批注、样式表一概不读，
 * 既省内存也避免把无关内容塞进提示词。
 *
 * 安全性上照 [cn.nabr.chatwithchat.data.textskill.TextSkillPackageRepository] 的有界解压写法办：
 * 条目数封顶、边读边累计字节数封顶（防 zip bomb），XML 禁用 DOCTYPE（防 XXE / 实体膨胀）。
 */
internal object DocxTextExtractor {
    private const val PRIMARY_DOCUMENT_ENTRY = "word/document.xml"
    private const val FALLBACK_ENTRY_PREFIX = "word/document"
    private const val DISALLOW_DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl"

    fun extract(file: File, maxChars: Int): DocumentText? {
        val documentXml = try {
            readDocumentXml(file)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            // 畸形 zip 条目名会让 ZipInputStream 抛这个
            null
        } ?: return null
        return parse(documentXml, maxChars)
    }

    internal fun parse(documentXml: ByteArray, maxChars: Int): DocumentText? {
        val parser = try {
            SAXParserFactory.newInstance().apply {
                // 关掉命名空间后 qName 直接是 "w:t"，省掉一层 URI 匹配
                isNamespaceAware = false
                setFeature(DISALLOW_DOCTYPE_FEATURE, true)
            }.newSAXParser()
        } catch (e: Exception) {
            return null
        }

        val handler = DocxTextHandler(maxChars)
        try {
            parser.parse(ByteArrayInputStream(documentXml), handler)
        } catch (e: DocxTextLimitReached) {
            // 到达字符上限时主动中止解析，属于预期路径
        } catch (e: SAXException) {
            return null
        } catch (e: IOException) {
            return null
        }

        val text = handler.build()
        if (text.isBlank()) return null
        return DocumentText(text, text.length, handler.truncated)
    }

    private fun readDocumentXml(file: File): ByteArray? {
        var fallback: ByteArray? = null
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > DocumentTextLimits.MAX_DOCX_ZIP_ENTRIES) return null
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val isPrimary = name.equals(PRIMARY_DOCUMENT_ENTRY, ignoreCase = true)
                val isFallback = fallback == null &&
                    name.startsWith(FALLBACK_ENTRY_PREFIX, ignoreCase = true) &&
                    name.endsWith(".xml", ignoreCase = true)
                if (!isPrimary && !isFallback) continue

                val bytes = readBounded(zip) { read ->
                    totalBytes += read
                    totalBytes <= DocumentTextLimits.MAX_DOCX_XML_BYTES
                } ?: return null
                if (isPrimary) return bytes
                fallback = bytes
            }
        }
        return fallback
    }

    private inline fun readBounded(input: InputStream, onRead: (Int) -> Boolean): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (!onRead(read)) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

private class DocxTextLimitReached : SAXException()

private class DocxTextHandler(private val maxChars: Int) : DefaultHandler() {
    private val builder = StringBuilder()
    private var skipDepth = 0
    private var cellDepth = 0
    private var inTextRun = false

    var truncated = false
        private set

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        if (skipDepth > 0) {
            skipDepth++
            return
        }
        when (tagName(qName)) {
            // 域代码（HYPERLINK 之类）和被删除的修订文本不属于正文
            "instrText", "delText" -> skipDepth = 1
            "t" -> inTextRun = true
            "tab" -> append("\t")
            "br", "cr" -> append("\n")
            "tc" -> cellDepth++
            // 单元格里的第二个及之后的段落用空格分隔：换行会让一行表格散成多行，行结构就丢了。
            "p" -> if (cellDepth > 0 && builder.isNotEmpty() && !builder.last().isWhitespace()) append(" ")
            else -> Unit
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (skipDepth > 0) {
            skipDepth--
            return
        }
        when (tagName(qName)) {
            "t" -> inTextRun = false
            // 段落换行交给单元格/行分隔符处理，否则一行表格会变成每格一行
            "p" -> if (cellDepth == 0) append("\n")
            "tc" -> {
                if (cellDepth > 0) cellDepth--
                append("\t")
            }

            "tr" -> append("\n")
            else -> Unit
        }
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (skipDepth > 0 || !inTextRun || ch == null || length <= 0) return
        append(String(ch, start, length))
    }

    fun build(): String = builder.toString()
        .replace(TRAILING_BLANKS, "\n")
        .replace(EXCESS_NEWLINES, "\n\n")
        .trim()

    private fun append(value: String) {
        if (value.isEmpty()) return
        val remaining = maxChars - builder.length
        if (remaining <= 0) {
            truncated = true
            throw DocxTextLimitReached()
        }
        if (value.length > remaining) {
            builder.append(value, 0, remaining)
            truncated = true
            throw DocxTextLimitReached()
        }
        builder.append(value)
    }

    private fun tagName(qName: String?): String = qName?.substringAfterLast(':') ?: ""

    private companion object {
        // 表格单元格结束补的 \t 落在行尾就是噪音
        val TRAILING_BLANKS = Regex("[ \\t]+\n")
        val EXCESS_NEWLINES = Regex("\n{3,}")
    }
}
