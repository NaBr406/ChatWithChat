package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.util.AttachmentPayloadCache
import cn.nabr.chatwithchat.util.FileUtils
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Discarding a draft must take its cached payload with it, and nothing else.
 *
 * [AttachmentPayloadCache] is a process-level object keyed on file paths, shared by every chat and by
 * the repository's encoder. Leaving a chat used to end in a blanket `clear()`, which discarded payloads
 * other chats were still using — wasteful today, and once turns outlive their screen, capable of pulling
 * an attachment out from under a request in flight. So the eviction has to be exact in both directions:
 * every path whose file is deleted, and no path whose file survives.
 */
class DiscardDraftFilesTest {

    @Before
    fun setUp() {
        AttachmentPayloadCache.clear()
    }

    @Test
    fun `a discarded owned file loses its cached payload`() {
        val prepared = tempFile()
        AttachmentPayloadCache.put(prepared.absolutePath, payload())

        discardDraftFiles(
            ChatAttachmentDraft(
                sourceFilePath = prepared.absolutePath,
                preparedFilePath = prepared.absolutePath,
                cleanupOnDiscard = true,
                cleanupPreparedOnDiscard = true
            )
        )

        assertFalse("the file should be gone", prepared.exists())
        assertNull("its payload must not outlive it", AttachmentPayloadCache.get(prepared.absolutePath))
    }

    /**
     * The case the blanket clear got wrong. A draft that only owns its prepared copy — an image picked
     * from outside the app — must not touch the original, and must not touch anything belonging to
     * another chat.
     */
    @Test
    fun `an unowned path keeps both its file and its payload`() {
        val external = tempFile()
        val prepared = tempFile()
        val anotherChatsFile = tempFile()
        AttachmentPayloadCache.put(external.absolutePath, payload())
        AttachmentPayloadCache.put(prepared.absolutePath, payload())
        AttachmentPayloadCache.put(anotherChatsFile.absolutePath, payload())

        discardDraftFiles(
            ChatAttachmentDraft(
                sourceFilePath = external.absolutePath,
                preparedFilePath = prepared.absolutePath,
                cleanupOnDiscard = false,
                cleanupPreparedOnDiscard = true
            )
        )

        assertFalse(prepared.exists())
        assertNull(AttachmentPayloadCache.get(prepared.absolutePath))
        assertTrue("the external original is not ours to delete", external.exists())
        assertNotNull(AttachmentPayloadCache.get(external.absolutePath))
        assertTrue(anotherChatsFile.exists())
        assertNotNull(
            "another chat's payload must survive this teardown",
            AttachmentPayloadCache.get(anotherChatsFile.absolutePath)
        )
    }

    @Test
    fun `a persisted attachment discards nothing`() {
        val history = tempFile()
        AttachmentPayloadCache.put(history.absolutePath, payload())

        discardDraftFiles(
            ChatAttachmentDraft(
                sourceFilePath = history.absolutePath,
                preparedFilePath = history.absolutePath,
                cleanupOnDiscard = false,
                cleanupPreparedOnDiscard = false
            )
        )

        assertTrue(history.exists())
        assertNotNull(AttachmentPayloadCache.get(history.absolutePath))
    }

    private fun tempFile(): File = File.createTempFile("draft", ".png").apply {
        writeBytes(ByteArray(8))
        deleteOnExit()
    }

    private fun payload() = FileUtils.EncodedImage("image/png", "payload")
}
