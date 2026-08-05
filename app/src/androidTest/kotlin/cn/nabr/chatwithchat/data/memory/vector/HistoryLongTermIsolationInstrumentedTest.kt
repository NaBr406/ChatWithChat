package cn.nabr.chatwithchat.data.memory.vector

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2Migrations
import cn.nabr.chatwithchat.data.database.ChatHistoryDatabaseCallback
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MemoryChatCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.history.ChatHistoryIndexCoordinator
import cn.nabr.chatwithchat.data.history.ChatHistoryProjectionBuilder
import cn.nabr.chatwithchat.data.history.ChatHistoryWorkScheduler
import cn.nabr.chatwithchat.data.history.FakeSettingRepository
import cn.nabr.chatwithchat.data.history.RoomHistoryVectorStore
import cn.nabr.chatwithchat.data.memory.MemoryChunker
import cn.nabr.chatwithchat.data.memory.MemoryCorpus
import cn.nabr.chatwithchat.data.memory.MemoryCorpusChunk
import cn.nabr.chatwithchat.data.memory.MemoryCorpusSnapshotter
import cn.nabr.chatwithchat.data.memory.MemoryFilePaths
import cn.nabr.chatwithchat.data.memory.MemoryFileStore
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingDescriptor
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingPooling
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingProvider
import cn.nabr.chatwithchat.data.memory.embedding.MutableMemoryEmbeddingCapabilitySource
import io.objectbox.BoxStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryLongTermIsolationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: ChatDatabaseV2
    private lateinit var memoryRoot: File
    private lateinit var vectorDirectory: File
    private lateinit var longTermStore: ObjectBoxMemoryVectorStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        memoryRoot = File(context.filesDir, "history-isolation-memory-${System.nanoTime()}")
        vectorDirectory = File(context.noBackupFilesDir, "history-isolation-vector-${System.nanoTime()}")
        BoxStore.deleteAllFiles(vectorDirectory)
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_isolation_${System.nanoTime()}"
        )
            .addMigrations(
                ChatDatabaseV2Migrations.MIGRATION_1_2,
                ChatDatabaseV2Migrations.MIGRATION_2_3,
                ChatDatabaseV2Migrations.MIGRATION_3_4,
                ChatDatabaseV2Migrations.MIGRATION_4_5,
                ChatDatabaseV2Migrations.MIGRATION_5_6,
                ChatDatabaseV2Migrations.MIGRATION_6_7,
                ChatDatabaseV2Migrations.MIGRATION_7_8,
                ChatDatabaseV2Migrations.MIGRATION_8_9,
                ChatDatabaseV2Migrations.MIGRATION_9_10,
                ChatDatabaseV2Migrations.MIGRATION_10_11,
                ChatDatabaseV2Migrations.MIGRATION_11_12,
                ChatDatabaseV2Migrations.MIGRATION_12_13,
                ChatDatabaseV2Migrations.MIGRATION_13_14,
                ChatDatabaseV2Migrations.MIGRATION_14_15,
                ChatDatabaseV2Migrations.MIGRATION_15_16,
                ChatDatabaseV2Migrations.MIGRATION_16_17,
                ChatDatabaseV2Migrations.MIGRATION_17_18,
                ChatDatabaseV2Migrations.MIGRATION_18_19,
                ChatDatabaseV2Migrations.MIGRATION_19_20
            )
            .addCallback(ChatHistoryDatabaseCallback())
            .build()
        longTermStore = ObjectBoxMemoryVectorStore(
            context = context,
            directory = MemoryVectorStoreDirectory.testing(context, vectorDirectory)
        )
    }

    @After
    fun tearDown() {
        database.close()
        longTermStore.close()
        BoxStore.deleteAllFiles(vectorDirectory)
        vectorDirectory.deleteRecursively()
        memoryRoot.deleteRecursively()
    }

    @Test
    fun historyIndexingLeavesLongTermMarkdownAndVectorSnapshotByteIdentical() = runBlocking {
        val fileStore = MemoryFileStore(MemoryFilePaths(memoryRoot))
        fileStore.ensureStore().getOrThrow()
        fileStore.replaceLongTermMemory(
            "# ChatWithChat Memory\n\n- The stable long-term fact must not change."
        ).getOrThrow()
        val memoryFile = MemoryFilePaths(memoryRoot).longTermMemoryFile
        val beforeBytes = Files.readAllBytes(memoryFile.toPath())
        val beforeMemorySnapshot = MemoryCorpusSnapshotter(fileStore, MemoryChunker())
            .snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM)
            .getOrThrow()
            .single()

        val longTermIdentity = MemoryVectorIndexDefaults.configuration.identity(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            recallProjectionHash = "a".repeat(64),
            corpusGeneration = 1
        )
        val longTermSnapshot = MemoryVectorSnapshot(
            manifest = MemoryVectorManifest(
                identity = longTermIdentity,
                expectedChunkCount = 1,
                completedAt = 1,
                state = MemoryVectorManifestState.READY
            ),
            chunks = listOf(
                MemoryEmbeddedChunk(
                    chunk = MemoryCorpusChunk(
                        chunkId = "MEMORY.md#stable#0",
                        entryId = "stable",
                        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                        chunkIndex = 0,
                        heading = null,
                        text = "The stable long-term fact must not change.",
                        type = "stable_profile",
                        sensitivity = "normal",
                        source = "explicit_user_statement",
                        chatId = null,
                        createdAt = 1,
                        updatedAt = 1
                    ),
                    embedding = FloatArray(MEMORY_VECTOR_DIMENSION).also { it[0] = 1f }
                )
            )
        )
        assertEquals(MemoryVectorPublishResult.PUBLISHED, longTermStore.replaceSnapshot(longTermSnapshot))
        val beforeManifest = longTermStore.readManifest()
        val beforeCount = longTermStore.countChunks()

        database.chatRoomDao().addChatRoom(
            ChatRoomV2(id = 120, title = "History only", enabledPlatform = listOf("provider"))
        )
        database.messageDao().addMessages(
            MessageV2(id = 1_200, chatId = 120, content = "history-only question", platformType = null, createdAt = 1),
            MessageV2(id = 1_201, chatId = 120, content = "history-only answer", platformType = "provider", createdAt = 2)
        )
        database.memoryTurnBatchDao().upsertCheckpoint(
            MemoryChatCheckpoint(
                chatId = 120,
                lastObservedUserMessageId = 1_200,
                updatedAt = 3
            )
        )
        database.memoryTurnBatchDao().upsertPendingTurn(
            MemoryPendingTurn(
                turnKey = "chat:120:user:1200",
                chatId = 120,
                userMessageId = 1_200,
                payloadJson = "{\"source\":\"isolation-test\"}",
                contentHash = "memory-source-hash",
                completedAt = 2,
                createdAt = 2,
                updatedAt = 2
            )
        )
        val beforeMessages = database.messageDao().loadMessages(120)
        val beforeLongTermTables = snapshotLongTermTables()

        val capability = MutableMemoryEmbeddingCapabilitySource()
        val provider = HistoryIsolationEmbeddingProvider()
        capability.setReady(provider, MemoryVectorIndexDefaults.configuration.copy(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            embeddingDescriptor = provider.descriptor,
            chunkerVersion = "history-turn-v1"
        ))
        val historyVectorStore = RoomHistoryVectorStore(database, database.chatHistoryDao(), capability)
        val coordinator = ChatHistoryIndexCoordinator(
            database = database,
            historyDao = database.chatHistoryDao(),
            chatRoomDao = database.chatRoomDao(),
            messageDao = database.messageDao(),
            settingRepository = FakeSettingRepository(true),
            scheduler = object : ChatHistoryWorkScheduler {
                override fun enqueue() = Unit
            },
            projectionBuilder = ChatHistoryProjectionBuilder { 10L },
            vectorStore = historyVectorStore
        )
        coordinator.enqueueChatReconciliation(120)
        repeat(8) {
            if (!coordinator.processWork().hasMore) return@repeat
        }

        assertTrue(database.chatHistoryDao().vectorSnapshot("current") != null)
        assertEquals(beforeMessages, database.messageDao().loadMessages(120))
        assertEquals(beforeLongTermTables, snapshotLongTermTables())
        assertTrue(beforeBytes.contentEquals(Files.readAllBytes(memoryFile.toPath())))
        val afterMemorySnapshot = MemoryCorpusSnapshotter(fileStore, MemoryChunker())
            .snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM)
            .getOrThrow()
            .single()
        assertEquals(beforeMemorySnapshot.canonicalSourceHash, afterMemorySnapshot.canonicalSourceHash)
        assertEquals(beforeMemorySnapshot.recallProjectionHash, afterMemorySnapshot.recallProjectionHash)
        assertEquals(beforeMemorySnapshot.generation, afterMemorySnapshot.generation)
        assertEquals(beforeManifest, longTermStore.readManifest())
        assertEquals(beforeCount, longTermStore.countChunks())
    }

    private fun snapshotLongTermTables(): Map<String, List<List<String>>> = LONG_TERM_TABLES.associateWith { table ->
        database.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY 1").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { index -> cursorValue(cursor, index) })
                }
            }
        }
    }

    private fun cursorValue(cursor: Cursor, index: Int): String = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> "<null>"
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString(",")
        else -> cursor.getString(index).orEmpty()
    }

    private companion object {
        val LONG_TERM_TABLES = listOf(
            "memory_maintenance_job",
            "memory_mutation_group",
            "memory_mutation_receipt",
            "memory_corpus_state",
            "memory_distillation_checkpoint",
            "memory_long_term_consolidation_checkpoint",
            "memory_chat_checkpoint",
            "memory_pending_turn",
            "memory_activity_log"
        )
    }
}

private class HistoryIsolationEmbeddingProvider : MemoryEmbeddingProvider {
    override val descriptor = MemoryEmbeddingDescriptor(
        providerId = "history-isolation",
        runtimeVersion = "1",
        modelId = "history-isolation",
        modelVersion = "1",
        modelSha256 = "0".repeat(64),
        dimension = 4,
        normalized = true,
        tokenizerVersion = "test",
        tokenizerFingerprint = "1".repeat(64),
        maxInputTokens = 128,
        pooling = MemoryEmbeddingPooling.MEAN,
        queryPrefix = "",
        documentPrefix = ""
    )

    override suspend fun availability(): MemoryEmbeddingAvailability = MemoryEmbeddingAvailability.Available

    override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> =
        Result.success(texts.map { floatArrayOf(1f, 0f, 0f, 0f) })

    override suspend fun embedQuery(text: String): Result<FloatArray> =
        Result.success(floatArrayOf(1f, 0f, 0f, 0f))
}
