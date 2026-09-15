package cn.nabr.chatwithchat.data.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.model.ChatAttachment
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolExecutionContextFactory
import cn.nabr.chatwithchat.data.tool.ToolExecutionSessionState
import cn.nabr.chatwithchat.data.tool.ToolExecutor
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import cn.nabr.chatwithchat.data.tool.ViewImageToolProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualAssetRepositoryInstrumentedTest {

    private lateinit var database: ChatDatabaseV2
    private lateinit var repository: VisualAssetRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java)
            .allowMainThreadQueries()
            .build()
        val store = VisualAssetStore(context)
        repository = VisualAssetRepository(
            dao = database.visualAssetDao(),
            messageDao = database.messageDao(),
            store = store,
            compressor = VisualAssetCompressor(store)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun indexFoldResolveAndGarbageCollect() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "visual-source.png")
        writeBitmap(source, width = 2_800, height = 1_600)
        val chatId = database.chatRoomDao().addChatRoom(
            ChatRoomV2(title = "visual", enabledPlatform = emptyList())
        ).toInt()
        database.messageDao().addMessages(
            MessageV2(
                chatId = chatId,
                content = "image",
                attachments = listOf(
                    ChatAttachment(
                        localFilePath = source.absolutePath,
                        preparedFilePath = source.absolutePath,
                        displayName = "source.png",
                        mimeType = "image/png",
                        sizeBytes = source.length(),
                        width = 2_800,
                        height = 1_600
                    )
                ),
                platformType = null
            )
        )
        val message = database.messageDao().loadMessages(chatId).single()
        val visualRef = VisualAssetRepository.visualRef(message.id, 1)

        repository.indexMessage(message, turnOrdinal = 1)
        val original = repository.resolve(chatId, visualRef, VisualDetail.ORIGINAL)
        assertTrue(original is VisualAssetReadResult.Success)
        val originalAsset = (original as VisualAssetReadResult.Success).asset
        assertEquals("image/png", originalAsset.mimeType)
        assertTrue(File(originalAsset.filePath).isFile)

        repository.markMessagesFolded(chatId, listOf(message.id))
        val compacted = repository.resolve(chatId, visualRef, VisualDetail.AUTO)
        assertTrue("unexpected compacted result: $compacted", compacted is VisualAssetReadResult.Success)
        val compactedAsset = (compacted as VisualAssetReadResult.Success).asset
        assertEquals(VisualAssetProfile.COMPACTED_V1, compactedAsset.profile)
        assertTrue(maxOf(compactedAsset.width ?: 0, compactedAsset.height ?: 0) <= 2_048)
        assertTrue(compactedAsset.sizeBytes > 0)

        val originalPath = originalAsset.filePath
        val compactedPath = compactedAsset.filePath
        database.messageDao().deleteMessages(message)
        repository.backfillCatalog()
        assertFalse(File(originalPath).exists())
        assertFalse(File(compactedPath).exists())
        assertEquals(0, database.visualAssetDao().getAllAssets().size)
    }

    @Test
    fun alphaImageKeepsTransparency() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "visual-alpha.png")
        writeBitmap(source, width = 2_400, height = 800, transparentCorner = true)
        val chatId = database.chatRoomDao().addChatRoom(
            ChatRoomV2(title = "alpha", enabledPlatform = emptyList())
        ).toInt()
        database.messageDao().addMessages(
            MessageV2(
                chatId = chatId,
                content = "alpha",
                attachments = listOf(
                    ChatAttachment(
                        localFilePath = source.absolutePath,
                        preparedFilePath = source.absolutePath,
                        displayName = "alpha.png",
                        mimeType = "image/png",
                        sizeBytes = source.length()
                    )
                ),
                platformType = null
            )
        )
        val message = database.messageDao().loadMessages(chatId).single()
        repository.indexMessage(message, turnOrdinal = 1)
        repository.markMessagesFolded(chatId, listOf(message.id))

        val stored = database.visualAssetDao().getAllAssets().single()
        assertNull("compaction error: ${stored.compactionError}", stored.compactionError)
        assertNotNull(stored.compactedPath)
        assertTrue("compacted file missing: ${stored.compactedPath}", File(stored.compactedPath.orEmpty()).isFile)
        assertNotNull(
            database.visualAssetDao().getCatalogEntry(
                chatId,
                VisualAssetRepository.visualRef(message.id, 1)
            )
        )
        val resolved = repository.resolve(
            chatId,
            VisualAssetRepository.visualRef(message.id, 1),
            VisualDetail.AUTO
        )
        assertTrue("unexpected alpha result: $resolved", resolved is VisualAssetReadResult.Success)
        resolved as VisualAssetReadResult.Success
        val bitmap = android.graphics.BitmapFactory.decodeFile(resolved.asset.filePath)
        assertNotNull(bitmap)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        bitmap.recycle()
    }

    @Test
    fun viewImageExecutorKeepsLargeOriginalPixels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "visual-large-original.png")
        writeBitmap(source, width = 3_072, height = 2_048)
        val chatId = database.chatRoomDao().addChatRoom(
            ChatRoomV2(title = "large-original", enabledPlatform = emptyList())
        ).toInt()
        database.messageDao().addMessages(
            MessageV2(
                chatId = chatId,
                content = "large image",
                attachments = listOf(
                    ChatAttachment(
                        localFilePath = source.absolutePath,
                        preparedFilePath = source.absolutePath,
                        displayName = "large-original.png",
                        mimeType = "image/png",
                        sizeBytes = source.length(),
                        width = 3_072,
                        height = 2_048
                    )
                ),
                platformType = null
            )
        )
        val message = database.messageDao().loadMessages(chatId).single()
        val visualRef = VisualAssetRepository.visualRef(message.id, 1)
        repository.ensureChatIndexed(chatId)

        val call = ToolCall(
            id = "call-large-original",
            name = "view_image",
            arguments = """{"visual_ref":"$visualRef","detail":"original"}"""
        )
        val executionContext = ToolExecutionContextFactory()
            .create(call = call, chatId = chatId)
            .getOrThrow()
        val executor = ToolExecutor(ToolRegistry(listOf(ViewImageToolProvider(repository))))

        val result = executor.execute(
            call = call,
            activeToolNames = setOf("view_image"),
            executionContext = executionContext,
            sessionState = ToolExecutionSessionState(chatId = chatId, supportsVisualInput = true)
        )

        assertFalse(result.isError)
        assertEquals(1, result.visualAttachments.size)
        assertTrue(result.visualAttachments.single().base64Data.orEmpty().isNotEmpty())
    }

    @Test
    fun reindexingFoldedAssetPreservesCatalogState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "visual-reindex.png")
        writeBitmap(source, width = 2_400, height = 800)
        val chatId = database.chatRoomDao().addChatRoom(
            ChatRoomV2(title = "reindex", enabledPlatform = emptyList())
        ).toInt()
        database.messageDao().addMessages(
            MessageV2(
                chatId = chatId,
                content = "fold me",
                attachments = listOf(
                    ChatAttachment(
                        localFilePath = source.absolutePath,
                        preparedFilePath = source.absolutePath,
                        displayName = "reindex.png",
                        mimeType = "image/png",
                        sizeBytes = source.length()
                    )
                ),
                platformType = null
            )
        )
        val message = database.messageDao().loadMessages(chatId).single()
        val visualRef = VisualAssetRepository.visualRef(message.id, 1)

        repository.indexMessage(message, turnOrdinal = 1)
        repository.markMessagesFolded(chatId, listOf(message.id))

        val folded = database.visualAssetDao().getCatalogEntry(chatId, visualRef)
        assertNotNull(folded)
        assertNotNull(folded!!.foldedAt)
        assertEquals(VisualAssetProfile.COMPACTED_V1.storageValue, folded.compactedProfile)

        repository.indexMessage(message, turnOrdinal = 1)

        val reindexed = database.visualAssetDao().getCatalogEntry(chatId, visualRef)
        assertNotNull(reindexed)
        assertNotNull(reindexed!!.foldedAt)
        assertEquals(VisualAssetProfile.COMPACTED_V1.storageValue, reindexed.compactedProfile)
        val compactedAfterReindex = repository.resolve(chatId, visualRef, VisualDetail.COMPACT)
        assertTrue("unexpected compact after reindex: $compactedAfterReindex", compactedAfterReindex is VisualAssetReadResult.Success)
    }

    private fun writeBitmap(
        file: File,
        width: Int,
        height: Int,
        transparentCorner: Boolean = false
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (!transparentCorner) {
            canvas.drawColor(Color.rgb(32, 120, 200))
        } else {
            canvas.drawColor(Color.TRANSPARENT)
            canvas.drawRect(
                width / 4f,
                height / 4f,
                width.toFloat(),
                height.toFloat(),
                Paint().apply { color = Color.rgb(220, 80, 40) }
            )
        }
        FileOutputStream(file, false).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
    }
}
