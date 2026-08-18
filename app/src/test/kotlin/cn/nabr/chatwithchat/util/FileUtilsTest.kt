package cn.nabr.chatwithchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsTest {

    @Test
    fun `large images are downsampled until longest edge fits upload limit`() {
        assertEquals(2, FileUtils.calculateImageInSampleSize(4000, 3000, 2048))
        assertEquals(4, FileUtils.calculateImageInSampleSize(5000, 3750, 2048))
        assertEquals(8, FileUtils.calculateImageInSampleSize(9000, 6000, 2048))
    }

    @Test
    fun `images already within upload limit keep original size`() {
        assertEquals(1, FileUtils.calculateImageInSampleSize(2048, 1536, 2048))
        assertEquals(1, FileUtils.calculateImageInSampleSize(1600, 1200, 2048))
    }

    @Test
    fun `images resize only when they exceed dimension threshold`() {
        assertTrue(FileUtils.shouldResizeImage(4096, 2048))
        assertFalse(FileUtils.shouldResizeImage(2048, 2048))
        assertFalse(FileUtils.shouldResizeImage(1600, 1200))
    }

    @Test
    fun `large image files resize even when dimensions are within threshold`() {
        assertTrue(
            FileUtils.shouldResizeImageForUpload(
                originalWidth = 1600,
                originalHeight = 1200,
                fileSizeBytes = 9L * 1024 * 1024
            )
        )
        assertFalse(
            FileUtils.shouldResizeImageForUpload(
                originalWidth = 1600,
                originalHeight = 1200,
                fileSizeBytes = 2L * 1024 * 1024
            )
        )
    }

    @Test
    fun `draft upload limit is enforced across all selected attachments`() {
        assertTrue(FileUtils.wouldExceedTotalUploadLimit(45L * 1024 * 1024, 6L * 1024 * 1024))
        assertFalse(FileUtils.wouldExceedTotalUploadLimit(40L * 1024 * 1024, 5L * 1024 * 1024))
    }

    @Test
    fun `non image mime types are not supported by image upload pipeline`() {
        assertTrue(FileUtils.isInlineImageMimeType("image/jpeg"))
        assertFalse(FileUtils.isInlineImageMimeType("application/pdf"))
        assertFalse(FileUtils.isInlineImageMimeType("text/markdown"))
    }

    @Test
    fun `text document gate accepts text family and the whitelisted application types`() {
        assertTrue(FileUtils.isTextDocumentMimeType("text/plain"))
        assertTrue(FileUtils.isTextDocumentMimeType("text/markdown"))
        assertTrue(FileUtils.isTextDocumentMimeType("text/plain; charset=GBK"))
        assertTrue(FileUtils.isTextDocumentMimeType("application/pdf"))
        assertTrue(
            FileUtils.isTextDocumentMimeType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
        // 旧版二进制 .doc 与压缩包不在支持范围内，必须在准入处就被拒掉。
        assertFalse(FileUtils.isTextDocumentMimeType("application/msword"))
        assertFalse(FileUtils.isTextDocumentMimeType("application/zip"))
        assertFalse(FileUtils.isTextDocumentMimeType("application/octet-stream"))
        assertFalse(FileUtils.isTextDocumentMimeType("image/png"))
    }

    @Test
    fun `attachment admission accepts both images and text documents`() {
        assertTrue(FileUtils.isSupportedAttachmentMimeType("image/png"))
        assertTrue(FileUtils.isSupportedAttachmentMimeType("application/pdf"))
        assertFalse(FileUtils.isSupportedAttachmentMimeType("application/zip"))
    }

    @Test
    fun `mime type falls back to extension for document suffixes`() {
        assertEquals("text/markdown", FileUtils.getMimeTypeFromPath("notes.md"))
        assertEquals("text/plain", FileUtils.getMimeTypeFromPath("Main.kt"))
        assertEquals("text/csv", FileUtils.getMimeTypeFromPath("rows.csv"))
        assertEquals("application/pdf", FileUtils.getMimeTypeFromPath("paper.pdf"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            FileUtils.getMimeTypeFromPath("报告.docx")
        )
    }

    @Test
    fun `transparent upload formats preserve alpha capable bitmap config`() {
        assertTrue(FileUtils.shouldPreserveAlpha("image/png"))
        assertTrue(FileUtils.shouldPreserveAlpha("image/webp"))
        assertFalse(FileUtils.shouldPreserveAlpha("image/jpeg"))
    }

    @Test
    fun `streaming base64 encoder returns encoded data when writer succeeds`() {
        val encoded = FileUtils.encodeToBase64 { outputStream ->
            outputStream.write("hello".toByteArray())
            true
        }

        assertEquals("aGVsbG8=", encoded)
    }

    @Test
    fun `streaming base64 encoder returns null when writer fails`() {
        val encoded = FileUtils.encodeToBase64 { outputStream ->
            outputStream.write("ignored".toByteArray())
            false
        }

        assertNull(encoded)
    }
}
