package cn.nabr.chatwithchat.data.sticker

import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerRepositoryInstrumentedTest {
    @Test
    fun bundledAsset_opensThroughRepositoryAndDecodes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ChatDatabaseV2::class.java
        ).build()
        val assetStore = StickerAssetStore(context)
        val repository = StickerRepositoryImpl(
            database = database,
            stickerCatalogDao = database.stickerCatalogDao(),
            messageV2Dao = database.messageDao(),
            bundledStickerCatalogSeeder = BundledStickerCatalogSeeder(
                context = context,
                database = database,
                stickerCatalogDao = database.stickerCatalogDao(),
                assetStore = assetStore
            ),
            stickerImportService = StickerImportService(context, assetStore),
            assetStore = assetStore
        )

        try {
            repository.ensureInitialized()
            val bundledItems = database.stickerCatalogDao().getEnabledItemsWithAssets()
            assertEquals(EXPECTED_BUNDLED_STICKER_COUNT, bundledItems.size)
            val newPngItem = bundledItems.single { item -> item.item.stickerId == NEW_BUNDLED_STICKER_ID }
            assertEquals(NEW_BUNDLED_ASSET_KEY, newPngItem.asset?.assetKey)

            val input = repository.openAsset(NEW_BUNDLED_ASSET_KEY)
            assertNotNull("Expected repository to open the newly bundled PNG sticker", input)
            val bitmap = input?.use { stream -> BitmapFactory.decodeStream(stream) }

            assertNotNull("Expected repository asset stream to decode", bitmap)
            assertEquals(EXPECTED_BUNDLED_PNG_SIZE, bitmap!!.width)
            assertEquals(EXPECTED_BUNDLED_PNG_SIZE, bitmap.height)
            bitmap.recycle()
        } finally {
            database.close()
        }
    }

    private companion object {
        const val EXPECTED_BUNDLED_STICKER_COUNT = 60
        const val EXPECTED_BUNDLED_PNG_SIZE = 240
        const val NEW_BUNDLED_STICKER_ID = "builtin.reactions.heart_burst"
        const val NEW_BUNDLED_ASSET_KEY = "dba9e56ea591340374e3edecf341661beeabd48a0a8834aa3dbb051ddbac7f62"
    }
}
