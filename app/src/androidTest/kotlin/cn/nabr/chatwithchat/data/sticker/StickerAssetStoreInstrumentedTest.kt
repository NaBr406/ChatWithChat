package cn.nabr.chatwithchat.data.sticker

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.entity.StickerAssetEntity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerAssetStoreInstrumentedTest {
    @Test
    fun bundledStickerAssetsOpenAndDecode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = StickerAssetStore(context)

        bundledAssets(context).forEach { asset ->
            assertTrue("Expected bundled asset ${asset.relativePath} to match its manifest digest", store.verify(asset))
            val bitmap = store.open(asset)?.use { input ->
                BitmapFactory.decodeStream(input)
            }

            assertNotNull("Expected bundled asset ${asset.relativePath} to decode", bitmap)
            bitmap?.let { decodedBitmap ->
                assertEquals("Unexpected width for ${asset.relativePath}", asset.width, decodedBitmap.width)
                assertEquals("Unexpected height for ${asset.relativePath}", asset.height, decodedBitmap.height)
                decodedBitmap.recycle()
            }
        }
    }

    @Test
    fun stagingFilesUseCacheAndInvalidPromotionCleansThemUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = StickerAssetStore(context)
        val stagingFile = requireNotNull(store.createStagingFile())
        val cacheStickerRoot = File(context.cacheDir, "stickers").canonicalFile

        try {
            assertTrue(stagingFile.canonicalFile.toPath().startsWith(cacheStickerRoot.toPath()))
            stagingFile.writeText("fixture")

            assertTrue(store.promote(stagingFile, "invalid", "jpg") == null)
            assertFalse(stagingFile.exists())
        } finally {
            stagingFile.delete()
        }
    }

    @Test
    fun interruptedStagingFilesAreRemovedAfterRetentionWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = StickerAssetStore(context)
        val stagingFile = requireNotNull(store.createStagingFile())

        try {
            stagingFile.writeText("interrupted import")
            stagingFile.setLastModified(1L)

            store.cleanupStagingFiles(nowMillis = STICKER_STAGING_RETENTION_MILLIS + 2L)

            assertFalse(stagingFile.exists())
        } finally {
            stagingFile.delete()
        }
    }

    private companion object {
        fun bundledAssets(context: Context): List<StickerAssetEntity> {
            val manifest = context.assets.open(BUNDLED_MANIFEST_PATH).bufferedReader().use { input ->
                JSONObject(input.readText())
            }
            val items = manifest.getJSONArray("items")

            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                StickerAssetEntity(
                    assetKey = item.getString("sha256"),
                    storageKind = STICKER_STORAGE_KIND_BUNDLED,
                    relativePath = item.getString("assetPath"),
                    mediaKind = item.getString("mediaKind"),
                    mimeType = item.getString("mimeType"),
                    byteSize = item.getLong("byteSize"),
                    width = item.getInt("width"),
                    height = item.getInt("height")
                )
            }
        }

        const val BUNDLED_MANIFEST_PATH = "stickers/builtin.reactions/v1/manifest.json"
    }
}
