package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.data.attachment.DocumentText

/**
 * Process-wide cache of encoded attachment payloads, keyed on file path.
 *
 * Being process-wide is the point — the same image referenced from two chats encodes once — and it is
 * also the trap: no single screen owns any entry. Drop entries with [remove], for a path whose file is
 * actually gone; everything else is the LRU bounds' job.
 */
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

    /**
     * Test-only reset. Deliberately has no production caller: this cache is shared by every chat and by
     * the repository's encoder, so wiping it wholesale discards payloads other work is still using.
     */
    internal fun clear() {
        synchronized(this) {
            payloads.clear()
            documentTexts.clear()
        }
    }
}
