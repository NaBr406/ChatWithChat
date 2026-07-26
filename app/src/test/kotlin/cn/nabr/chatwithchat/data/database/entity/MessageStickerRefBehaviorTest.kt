package cn.nabr.chatwithchat.data.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessageStickerRefBehaviorTest {
    @Test
    fun `sticker only assistant message is persistable and snapshots its asset metadata`() {
        val sticker = MessageStickerRef(
            instanceId = "tool-call-1",
            stickerId = "builtin.reactions.crying_cat",
            assetKey = "a".repeat(64),
            altText = "一只委屈痛哭的小猫"
        )
        val message = MessageV2(
            content = "",
            stickerRefs = listOf(sticker),
            platformType = "provider-1"
        )

        val snapshot = message.snapshotLatestAssistantRevision(timestamp = 123L)

        assertFalse(message.isEffectivelyBlank())
        assertNotNull(snapshot)
        assertEquals(listOf(sticker), snapshot?.stickerRefs)
        assertEquals(123L, snapshot?.createdAt)
    }

    @Test
    fun `selected revision exposes its own sticker refs instead of latest refs`() {
        val historicalSticker = MessageStickerRef(
            instanceId = "tool-call-old",
            stickerId = "builtin.reactions.qq_penguin_heartbroken",
            assetKey = "b".repeat(64),
            altText = "一只心碎难过的企鹅"
        )
        val latestSticker = MessageStickerRef(
            instanceId = "tool-call-new",
            stickerId = "builtin.reactions.soul_stare_cat",
            assetKey = "c".repeat(64),
            altText = "一只大脸猫沉默凝视"
        )
        val message = MessageV2(
            content = "latest",
            stickerRefs = listOf(latestSticker),
            revisions = listOf(
                AssistantRevision(
                    content = "older",
                    createdAt = 1L,
                    stickerRefs = listOf(historicalSticker)
                )
            ),
            activeRevisionIndex = 0,
            platformType = "provider-1"
        )

        assertEquals(listOf(historicalSticker), message.effectiveStickerRefs())
        assertEquals(listOf(latestSticker), message.resetActiveRevision().effectiveStickerRefs())
    }
}
