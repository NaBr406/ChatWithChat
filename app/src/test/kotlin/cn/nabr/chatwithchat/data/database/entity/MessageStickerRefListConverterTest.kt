package cn.nabr.chatwithchat.data.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStickerRefListConverterTest {
    private val converter = MessageStickerRefListConverter()

    @Test
    fun `message sticker refs round trip including immutable display snapshot`() {
        val refs = listOf(
            MessageStickerRef(
                instanceId = "call-1",
                stickerId = "builtin.reactions.crying_cat",
                assetKey = "a".repeat(64),
                altText = "一只委屈痛哭的小猫"
            )
        )

        assertEquals(refs, converter.fromString(converter.fromList(refs)))
    }

    @Test
    fun `blank and malformed values decode to empty sticker refs`() {
        assertTrue(converter.fromString("").isEmpty())
        assertTrue(converter.fromString("[").isEmpty())
    }
}
