package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ChatAttachment
import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTokenCounterTest {

    private val counter = ContextTokenCounter.forPlatform(
        PlatformV2(
            uid = "platform-uid",
            name = "Custom",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://example.com",
            model = "custom-model"
        )
    )

    @Test
    fun `images keep the flat per attachment estimate`() {
        val estimate = counter.estimateMessage(message(attachment("image/png", documentCharCount = null)))

        assertEquals(ContextTokenCounter.ATTACHMENT_ESTIMATED_TOKENS, estimate - baseline())
    }

    @Test
    fun `documents are estimated from their character count`() {
        val charCount = 40_000
        val estimate = counter.estimateMessage(message(attachment("text/plain", documentCharCount = charCount)))

        // 正文会被内联进消息文本，四万字符按固定 850 估会低一个数量级。
        assertEquals(charCount / ContextTokenCounter.DOCUMENT_CHARS_PER_TOKEN, estimate - baseline())
        assertTrue(estimate > ContextTokenCounter.ATTACHMENT_ESTIMATED_TOKENS)
    }

    @Test
    fun `documents without a usable character count fall back to the flat estimate`() {
        val estimate = counter.estimateMessage(message(attachment("text/plain", documentCharCount = 0)))

        assertEquals(ContextTokenCounter.ATTACHMENT_ESTIMATED_TOKENS, estimate - baseline())
    }

    /** 不带附件时的消息开销，用来把附件的贡献单独择出来。 */
    private fun baseline(): Int = counter.estimateMessage(message())

    private fun message(vararg attachments: ChatAttachment) = MessageV2(
        content = "看看这个",
        attachments = attachments.toList(),
        platformType = null
    )

    private fun attachment(mimeType: String, documentCharCount: Int?) = ChatAttachment(
        localFilePath = "/tmp/a",
        preparedFilePath = "/tmp/a",
        displayName = "a",
        mimeType = mimeType,
        sizeBytes = 1_024L,
        documentCharCount = documentCharCount
    )
}
