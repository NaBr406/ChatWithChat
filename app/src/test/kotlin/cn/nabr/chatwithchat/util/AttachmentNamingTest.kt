package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.presentation.ui.chat.attachmentIdentity
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentNamingTest {

    @Test
    fun `original name survives a build and read back round trip`() {
        val fileName = AttachmentNaming.buildAttachmentFileName(DIGEST, "季度报告", "docx", UUID)

        assertEquals("${AttachmentNaming.PREFIX}${DIGEST}_${UUID}_季度报告.docx", fileName)
        assertEquals("季度报告.docx", AttachmentNaming.attachmentDisplayName(fileName))
    }

    @Test
    fun `underscores in the original name are preserved`() {
        val fileName = AttachmentNaming.buildAttachmentFileName(DIGEST, "my_notes_v2", "md", UUID)

        assertEquals("my_notes_v2.md", AttachmentNaming.attachmentDisplayName(fileName))
    }

    @Test
    fun `files without an extension keep their bare name`() {
        val fileName = AttachmentNaming.buildAttachmentFileName(DIGEST, "README", "", UUID)

        assertEquals("${AttachmentNaming.PREFIX}${DIGEST}_${UUID}_README", fileName)
        assertEquals("README", AttachmentNaming.attachmentDisplayName(fileName))
    }

    @Test
    fun `long cjk names are capped by bytes to stay under the filesystem limit`() {
        val fileName = AttachmentNaming.buildAttachmentFileName(DIGEST, "文".repeat(80), "txt", UUID)

        // ext4/f2fs 的单个文件名上限是 255 字节，一个汉字占 3 字节，按字符数封顶会溢出。
        assertTrue(fileName.toByteArray(StandardCharsets.UTF_8).size <= 200)
        assertEquals("文".repeat(27) + ".txt", AttachmentNaming.attachmentDisplayName(fileName))
    }

    @Test
    fun `names that do not follow the convention are returned as is`() {
        // 旧版本存下来的文件没有第三段，必须原样显示而不是空白。
        assertEquals(
            "${AttachmentNaming.PREFIX}${DIGEST}_$UUID.jpg",
            AttachmentNaming.attachmentDisplayName("${AttachmentNaming.PREFIX}${DIGEST}_$UUID.jpg")
        )
        assertEquals("photo.jpg", AttachmentNaming.attachmentDisplayName("photo.jpg"))
        assertEquals(
            "${AttachmentNaming.PREFIX}not-a-digest_$UUID.txt",
            AttachmentNaming.attachmentDisplayName("${AttachmentNaming.PREFIX}not-a-digest_$UUID.txt")
        )
    }

    @Test
    fun `dedupe identity still reads the digest out of the new name`() {
        val fileName = AttachmentNaming.buildAttachmentFileName(DIGEST, "季度报告", "docx", UUID)

        assertEquals("sha256:$DIGEST", attachmentIdentity("/data/user/0/app/files/$fileName"))
    }

    private companion object {
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val UUID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
