package cn.nabr.chatwithchat.data.sticker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerAssetStoreInstrumentedTest {
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
}
