package cn.nabr.chatwithchat.util

import org.junit.Assert.assertEquals
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
}
