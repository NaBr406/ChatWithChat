package cn.nabr.chatwithchat.data.sticker

import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSearchMatcherTest {
    @Test
    fun `an exact alias ranks ahead of a weaker text match`() {
        val asset = StickerAssetDescriptor(
            assetKey = "a".repeat(64),
            mediaKind = STICKER_MEDIA_KIND_STATIC_RASTER,
            mimeType = "image/jpeg"
        )
        val aliasMatch = StickerCatalogItem(
            stickerId = "builtin.reactions.qq_penguin_heartbroken",
            packId = STICKER_PACK_ID_BUILTIN_REACTIONS,
            title = "企鹅心碎",
            altText = "一只难过的企鹅",
            tags = listOf("难过"),
            aliases = listOf("heartbreak"),
            enabled = true,
            isBuiltin = true,
            asset = asset,
            updatedAt = 1L
        )
        val weakTextMatch = aliasMatch.copy(
            stickerId = "user.weak-match",
            title = "heartbreak story",
            aliases = emptyList()
        )

        val tokens = listOf("heartbreak")

        assertTrue(
            aliasMatch.stickerMatchScore("heartbreak", tokens) >
                weakTextMatch.stickerMatchScore("heartbreak", tokens)
        )
    }
}
