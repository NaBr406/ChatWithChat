package cn.nabr.chatwithchat.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerMetadataCodecTest {
    @Test
    fun `normalization bounds model visible metadata and removes control characters`() {
        val normalized = StickerMetadataCodec.normalize(
            StickerItemMetadata(
                title = "  title\n" + "a".repeat(100),
                altText = "alt\u0000text " + "b".repeat(200),
                tags = List(14) { index -> " tag$index\t" + "c".repeat(40) },
                aliases = List(14) { index -> " alias$index\t" + "d".repeat(40) }
            )
        )

        requireNotNull(normalized)
        assertEquals(80, normalized.title.length)
        assertEquals(160, normalized.altText.length)
        assertEquals(12, normalized.tags.size)
        assertEquals(12, normalized.aliases.size)
        assertTrue(
            (normalized.tags + normalized.aliases).all { tag ->
                tag.length <= 32 && tag.none { character -> character.code in 0..31 || character.code == 127 }
            }
        )
    }

    @Test
    fun `normalization rejects missing semantic labels`() {
        assertNull(
            StickerMetadataCodec.normalize(
                StickerItemMetadata(title = " \n", altText = "valid", tags = emptyList())
            )
        )
        assertNull(
            StickerMetadataCodec.normalize(
                StickerItemMetadata(title = "valid", altText = "\u0000", tags = emptyList())
            )
        )
    }

    @Test
    fun `decoded persisted tags remain bounded even when stored data is malformed for the UI contract`() {
        val tags = StickerMetadataCodec.decodeTags(
            "[\" valid \", \"\\u0000\", \"${"x".repeat(80)}\", \"valid\"]"
        )

        assertEquals(listOf("valid", "x".repeat(32)), tags)
    }
}
