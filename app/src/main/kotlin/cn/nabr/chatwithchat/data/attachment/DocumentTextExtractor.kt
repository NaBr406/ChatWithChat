package cn.nabr.chatwithchat.data.attachment

import android.content.Context
import cn.nabr.chatwithchat.util.AttachmentPayloadCache
import cn.nabr.chatwithchat.util.FileUtils
import cn.nabr.chatwithchat.util.withoutUnpairedSurrogates
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一份文档在本地抽取出来的正文。[charCount] 是 [text] 的长度，冗余存一份是为了写进
 * `ChatAttachment` 后不必再解一次文件就能估 token。
 */
data class DocumentText(
    val text: String,
    val charCount: Int,
    val truncated: Boolean
)

/**
 * 把文字类附件（纯文本 / docx / PDF）在本地解成正文，供提示词合成与 UI 预览共用。
 *
 * 抽取结果只进 [AttachmentPayloadCache] 的内存缓存，不落库——四万字符写进 Room 的 JSON 列
 * 会让历史记录体积失控，而重抽一次的代价很低。
 */
object DocumentTextExtractor {
    internal const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    internal const val PDF_MIME_TYPE = "application/pdf"

    private val pdfTextReader: PdfTextReader by lazy { PdfBoxTextReader() }

    private val gbkCharset: Charset? by lazy {
        try {
            Charset.forName("GBK")
        } catch (e: Exception) {
            null
        }
    }

    suspend fun extract(context: Context, filePath: String, mimeType: String): DocumentText? {
        AttachmentPayloadCache.getDocumentText(filePath)?.let { return it }
        val extracted = withContext(Dispatchers.IO) { extractBlocking(context, filePath, mimeType) } ?: return null
        AttachmentPayloadCache.putDocumentText(filePath, extracted)
        return extracted
    }

    private fun extractBlocking(context: Context, filePath: String, mimeType: String): DocumentText? {
        if (!FileUtils.isTextDocumentMimeType(mimeType)) return null
        val file = File(filePath)
        if (!file.isFile || file.length() <= 0L) return null

        val raw = when {
            mimeType.equals(PDF_MIME_TYPE, ignoreCase = true) -> {
                if (file.length() > DocumentTextLimits.MAX_PDF_BYTES) {
                    null
                } else {
                    pdfTextReader.readText(context, file, DocumentTextLimits.MAX_DOCUMENT_CHARS)
                }
            }

            mimeType.equals(DOCX_MIME_TYPE, ignoreCase = true) -> {
                DocxTextExtractor.extract(file, DocumentTextLimits.MAX_DOCUMENT_CHARS)
            }

            else -> readPlainText(file)
        } ?: return null

        if (raw.text.isBlank()) return null
        val normalized = truncateDocumentText(raw.text, DocumentTextLimits.MAX_DOCUMENT_CHARS)
        return if (raw.truncated && !normalized.truncated) normalized.copy(truncated = true) else normalized
    }

    private fun readPlainText(file: File): DocumentText? {
        if (file.length() > DocumentTextLimits.MAX_PLAIN_TEXT_BYTES) return null
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        val decoded = decodeTextBytes(bytes) ?: return null
        return DocumentText(decoded, decoded.length, truncated = false)
    }

    /**
     * 尽力把字节解成文本：先认 BOM，再严格 UTF-8，最后回退 GBK（中文 Windows 存出来的 .txt
     * 基本都是 GBK）。解码器一律用 REPORT 而不是替换字符，否则二进制文件会变成一屏 `�`。
     * 返回 null 表示"这不是文本"。
     */
    internal fun decodeTextBytes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null

        bomCharset(bytes)?.let { (charset, bomLength) ->
            return decodeStrictly(bytes, bomLength, charset)
        }
        // BOM 判定必须在前：UTF-16 文本里 ASCII 字符自带 NUL 字节，会被误判成二进制。
        if (looksBinary(bytes)) return null

        decodeStrictly(bytes, 0, StandardCharsets.UTF_8)?.let { return it }
        return decodeStrictly(bytes, 0, gbkCharset)
    }

    /**
     * 修掉落单代理项、归一化换行，再按 [maxChars] 截断，返回值里带上是否截断。
     *
     * 清洗放在这里而不是发送前：正文同时喂给预览页和提示词，两边看到的应当是同一份文本；
     * 而且 PDFBox 是唯一会产出落单代理项的抽取器，堵在源头最省事。
     */
    internal fun truncateDocumentText(text: String, maxChars: Int): DocumentText {
        val normalized = text.withoutUnpairedSurrogates().replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.length <= maxChars) {
            return DocumentText(normalized, normalized.length, truncated = false)
        }
        // 不要把代理对切成两半，否则末尾会多出一个孤立的半个 emoji。
        val end = if (maxChars > 0 && normalized[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
        val clipped = normalized.substring(0, end)
        return DocumentText(clipped, clipped.length, truncated = true)
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            StandardCharsets.UTF_8 to 3

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            StandardCharsets.UTF_16LE to 2

        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            StandardCharsets.UTF_16BE to 2

        else -> null
    }

    private fun decodeStrictly(bytes: ByteArray, offset: Int, charset: Charset?): String? {
        if (charset == null || offset > bytes.size) return null
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, DocumentTextLimits.BINARY_SNIFF_BYTES)
        for (index in 0 until limit) {
            if (bytes[index] == 0.toByte()) return true
        }
        return false
    }
}
