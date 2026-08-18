package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.data.attachment.DocumentText

object AttachmentPayloadCache {
    internal const val MAX_ENTRIES: Int = 32

    /** 文档正文单条可达四万字符，缓存条数要比图片小得多。 */
    internal const val MAX_DOCUMENT_ENTRIES: Int = 8

    private val payloads = object : LinkedHashMap<String, FileUtils.EncodedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileUtils.EncodedImage>?): Boolean = size > MAX_ENTRIES
    }

    private val documentTexts = object : LinkedHashMap<String, DocumentText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DocumentText>?): Boolean = size > MAX_DOCUMENT_ENTRIES
    }

    fun get(filePath: String): FileUtils.EncodedImage? = synchronized(this) {
        payloads[filePath]
    }

    fun put(filePath: String, payload: FileUtils.EncodedImage) {
        synchronized(this) {
            payloads[filePath] = payload
        }
    }

    fun getDocumentText(filePath: String): DocumentText? = synchronized(this) {
        documentTexts[filePath]
    }

    fun putDocumentText(filePath: String, documentText: DocumentText) {
        synchronized(this) {
            documentTexts[filePath] = documentText
        }
    }

    fun remove(filePath: String) {
        synchronized(this) {
            payloads.remove(filePath)
            documentTexts.remove(filePath)
        }
    }

    internal fun clear() {
        synchronized(this) {
            payloads.clear()
            documentTexts.clear()
        }
    }
}
