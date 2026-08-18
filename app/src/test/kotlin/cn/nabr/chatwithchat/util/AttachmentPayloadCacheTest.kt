package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.data.attachment.DocumentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentPayloadCacheTest {

    @Test
    fun `cache evicts oldest entries when capacity is exceeded`() {
        AttachmentPayloadCache.clear()

        repeat(AttachmentPayloadCache.MAX_ENTRIES + 1) { index ->
            AttachmentPayloadCache.put(
                "file-$index",
                FileUtils.EncodedImage(
                    mimeType = "image/jpeg",
                    base64Data = "payload-$index"
                )
            )
        }

        assertNull(AttachmentPayloadCache.get("file-0"))
        assertEquals("payload-1", AttachmentPayloadCache.get("file-1")?.base64Data)
    }

    @Test
    fun `document cache has its own smaller capacity`() {
        AttachmentPayloadCache.clear()

        repeat(AttachmentPayloadCache.MAX_DOCUMENT_ENTRIES + 1) { index ->
            AttachmentPayloadCache.putDocumentText("doc-$index", documentText("body-$index"))
        }

        // 单条正文可达四万字符，条数上限必须比图片小得多。
        assertNull(AttachmentPayloadCache.getDocumentText("doc-0"))
        assertEquals("body-1", AttachmentPayloadCache.getDocumentText("doc-1")?.text)
    }

    @Test
    fun `reading a document entry refreshes its position in the lru`() {
        AttachmentPayloadCache.clear()

        repeat(AttachmentPayloadCache.MAX_DOCUMENT_ENTRIES) { index ->
            AttachmentPayloadCache.putDocumentText("doc-$index", documentText("body-$index"))
        }
        AttachmentPayloadCache.getDocumentText("doc-0")
        AttachmentPayloadCache.putDocumentText("doc-new", documentText("body-new"))

        assertNotNull(AttachmentPayloadCache.getDocumentText("doc-0"))
        assertNull(AttachmentPayloadCache.getDocumentText("doc-1"))
    }

    @Test
    fun `invalidating a path clears both the image and the document entry`() {
        AttachmentPayloadCache.clear()

        AttachmentPayloadCache.put("same-path", FileUtils.EncodedImage("image/png", "payload"))
        AttachmentPayloadCache.putDocumentText("same-path", documentText("body"))
        AttachmentPayloadCache.remove("same-path")

        // 草稿删除等失效点只调 remove 一次，两张表都得清干净。
        assertNull(AttachmentPayloadCache.get("same-path"))
        assertNull(AttachmentPayloadCache.getDocumentText("same-path"))
    }

    private fun documentText(text: String) = DocumentText(text = text, charCount = text.length, truncated = false)
}
