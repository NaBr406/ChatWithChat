package cn.nabr.chatwithchat.data.sticker

import org.junit.Assert.assertEquals
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

    @Test
    fun `generic sticker queries return a bounded candidate score`() {
        val item = StickerCatalogItem(
            stickerId = "builtin.reactions.crying_cat",
            packId = STICKER_PACK_ID_BUILTIN_REACTIONS,
            title = "小猫痛哭",
            altText = "一只委屈痛哭的小猫",
            tags = listOf("悲伤"),
            enabled = true,
            isBuiltin = true,
            asset = StickerAssetDescriptor(
                assetKey = "b".repeat(64),
                mediaKind = STICKER_MEDIA_KIND_STATIC_RASTER,
                mimeType = "image/jpeg"
            ),
            updatedAt = 1L
        )

        listOf(
            "表情",
            "测试",
            "试试",
            "试一下",
            "尝试",
            "看看",
            "表情试试",
            "发张表情试试",
            "随便",
            "sticker",
            "test",
            "try"
        ).forEach { query ->
            assertTrue(query, item.stickerMatchScore(query, listOf(query)) > 0)
        }
        assertEquals(0, item.stickerMatchScore("quantum", listOf("quantum")))
    }

    @Test
    fun `semantic phrase ranks above generic intent embedded in chinese request`() {
        val asset = StickerAssetDescriptor(
            assetKey = "c".repeat(64),
            mediaKind = STICKER_MEDIA_KIND_STATIC_RASTER,
            mimeType = "image/png"
        )
        val sadItem = StickerCatalogItem(
            stickerId = "builtin.reactions.sad",
            packId = STICKER_PACK_ID_BUILTIN_REACTIONS,
            title = "难过小猫",
            altText = "一只伤心难过的小猫",
            tags = listOf("难过", "伤心"),
            enabled = true,
            isBuiltin = true,
            asset = asset,
            updatedAt = 1L
        )
        val happyItem = sadItem.copy(
            stickerId = "builtin.reactions.happy",
            title = "开心小猫",
            altText = "一只快乐的小猫",
            tags = listOf("开心", "快乐")
        )
        val query = "发张难过表情试试"

        assertTrue(
            sadItem.stickerMatchScore(query, listOf(query)) >
                happyItem.stickerMatchScore(query, listOf(query))
        )
    }
}
