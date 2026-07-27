package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.sticker.STICKER_MEDIA_KIND_STATIC_RASTER
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerMessageBlockInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bundledAsset_rendersInsteadOfUnavailablePlaceholder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unavailableText = context.getString(R.string.sticker_unavailable)
        val altText = "一只心碎难过的企鹅"
        val assetResolver = object : StickerAssetResolver {
            override suspend fun openStickerAsset(assetKey: String) = context.assets.open(BUNDLED_ASSET_PATH)
        }

        composeRule.setContent {
            ChatWithChatTheme {
                StickerAssetPreview(
                    assetKey = BUNDLED_ASSET_KEY,
                    altText = altText,
                    mediaKind = STICKER_MEDIA_KIND_STATIC_RASTER,
                    assetResolver = assetResolver,
                    size = STICKER_PREVIEW_SIZE
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(altText).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithText(unavailableText).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(altText).assertIsDisplayed()
    }

    @Test
    fun unreadableAsset_rendersUnavailablePlaceholder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unavailableText = context.getString(R.string.sticker_unavailable)
        val assetResolver = object : StickerAssetResolver {
            override suspend fun openStickerAsset(assetKey: String): InputStream = object : InputStream() {
                override fun read(): Int = throw IOException("test read failure")
            }
        }

        composeRule.setContent {
            ChatWithChatTheme {
                StickerAssetPreview(
                    assetKey = "0".repeat(64),
                    altText = "损坏的表情",
                    mediaKind = STICKER_MEDIA_KIND_STATIC_RASTER,
                    assetResolver = assetResolver,
                    size = STICKER_PREVIEW_SIZE
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(unavailableText).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(unavailableText).assertIsDisplayed()
    }

    @Test
    fun changingAsset_hidesPreviousBitmapUntilReplacementLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val allowReplacement = CompletableDeferred<Unit>()
        var selectedAssetKey by mutableStateOf(SWITCH_ASSET_KEY_A)
        val assetResolver = object : StickerAssetResolver {
            override suspend fun openStickerAsset(assetKey: String): InputStream {
                if (assetKey == SWITCH_ASSET_KEY_B) allowReplacement.await()
                val path = if (assetKey == SWITCH_ASSET_KEY_A) {
                    BUNDLED_ASSET_PATH
                } else {
                    SECOND_BUNDLED_ASSET_PATH
                }
                return context.assets.open(path)
            }
        }

        composeRule.setContent {
            val assetKey = selectedAssetKey
            ChatWithChatTheme {
                StickerAssetPreview(
                    assetKey = assetKey,
                    altText = if (assetKey == SWITCH_ASSET_KEY_A) SWITCH_ALT_TEXT_A else SWITCH_ALT_TEXT_B,
                    mediaKind = STICKER_MEDIA_KIND_STATIC_RASTER,
                    assetResolver = assetResolver,
                    size = STICKER_PREVIEW_SIZE
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(SWITCH_ALT_TEXT_A).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.runOnUiThread { selectedAssetKey = SWITCH_ASSET_KEY_B }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(SWITCH_ALT_TEXT_A).fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithContentDescription(SWITCH_ALT_TEXT_B).fetchSemanticsNodes().isEmpty()
        }

        allowReplacement.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(SWITCH_ALT_TEXT_B).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(SWITCH_ALT_TEXT_B).assertIsDisplayed()
    }

    private companion object {
        const val BUNDLED_ASSET_KEY = "ffb2ef70da693e5340279baa57c42a6d2090a18d4f91431d150cc444228b4ed9"
        const val BUNDLED_ASSET_PATH = "stickers/builtin.reactions/v1/qq_penguin_heartbroken.jpg"
        const val SECOND_BUNDLED_ASSET_PATH = "stickers/builtin.reactions/v1/crying_cat.jpg"
        const val SWITCH_ASSET_KEY_A = "preview-switch-a"
        const val SWITCH_ASSET_KEY_B = "preview-switch-b"
        const val SWITCH_ALT_TEXT_A = "preview switch A"
        const val SWITCH_ALT_TEXT_B = "preview switch B"
        val STICKER_PREVIEW_SIZE = 64.dp
    }
}
