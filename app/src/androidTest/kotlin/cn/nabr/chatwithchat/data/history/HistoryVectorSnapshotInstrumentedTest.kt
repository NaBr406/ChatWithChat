package cn.nabr.chatwithchat.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2Migrations
import cn.nabr.chatwithchat.data.database.ChatHistoryDatabaseCallback
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryEmbeddingCacheEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.memory.MemoryCorpus
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingAvailability
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingDescriptor
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingPooling
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingProvider
import cn.nabr.chatwithchat.data.memory.embedding.MutableMemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorDistanceMetric
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexConfiguration
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryVectorSnapshotInstrumentedTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var provider: CountingEmbeddingProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_vector_${System.nanoTime()}"
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
        provider = CountingEmbeddingProvider()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun publishesIndependentSnapshotReusesCacheAndFallsBackWhenCorrupt() = runBlocking {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 70, title = "项目", enabledPlatform = emptyList()))
        val projection = ChatHistoryProjectionEntity(
            turnKey = "chat:70:user:1",
            chatId = 70,
            userMessageId = 1,
            assistantMessageId = 2,
            assistantPlatformUid = "provider",
            title = "项目",
            userContent = "项目排期",
            assistantContent = "已完成",
            searchTerms = ChatHistoryQueryNormalizer.indexTerms("项目排期 已完成").joinToString(" "),
            contentHash = "hash-project",
            projectionVersion = CURRENT_PROJECTION_VERSION,
            eligibilityState = HistoryEligibilityState.ELIGIBLE,
            createdAt = 1,
            updatedAt = 1
        )
        val dao = database.chatHistoryDao()
        dao.upsertProjection(projection)
        dao.upsertIndexState(
            ChatHistoryIndexStateEntity(
                stateId = HISTORY_INDEX_STATE_ID,
                projectionGeneration = 1,
                projectionHash = null,
                vectorStatus = HistoryVectorStatus.STALE,
                updatedAt = 1
            )
        )
        val capability = MutableMemoryEmbeddingCapabilitySource()
        capability.setReady(provider, MemoryVectorIndexConfiguration(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            indexSchemaVersion = 1,
            chunkerVersion = "history",
            maxChunkChars = 400,
            chunkOverlapChars = 0,
            markdownCodecVersion = "history",
            embeddingDescriptor = provider.descriptor,
            queryTextNormalization = "nfkc",
            documentTextNormalization = "nfkc",
            distanceMetric = MemoryVectorDistanceMetric.COSINE
        ))
        val store = RoomHistoryVectorStore(database, dao, capability)

        val first = store.publish(listOf(projection)).getOrThrow()
        assertEquals(1, first.embeddedCount)
        assertEquals(0, first.reusedCount)
        assertEquals(1, store.search("项目", 4).size)
        val second = store.publish(listOf(projection)).getOrThrow()
        assertEquals(0, second.embeddedCount)
        assertEquals(1, second.reusedCount)
        assertEquals(1, provider.documentCalls)
        dao.upsertEmbedding(
            ChatHistoryEmbeddingCacheEntity(
                turnKey = projection.turnKey,
                contentHash = projection.contentHash,
                descriptorHash = descriptorHash(provider),
                embedding = ByteArray(1),
                dimension = provider.descriptor.dimension,
                updatedAt = 2
            )
        )
        val repaired = store.publish(listOf(projection)).getOrThrow()
        assertEquals(1, repaired.embeddedCount)
        assertEquals(2, provider.documentCalls)

        dao.deleteVectorEntries(HISTORY_VECTOR_SNAPSHOT_ID)
        dao.deleteProjectionByKey(projection.turnKey)
        store.publish(emptyList()).getOrThrow()
        assertEquals(0, dao.vectorSnapshot(HISTORY_VECTOR_SNAPSHOT_ID)?.expectedCount)
        assertTrue(dao.vectorEntries(HISTORY_VECTOR_SNAPSHOT_ID).isEmpty())
        assertTrue(store.search("项目", 4).isEmpty())
    }
}

private class CountingEmbeddingProvider : MemoryEmbeddingProvider {
    override val descriptor = MemoryEmbeddingDescriptor(
        providerId = "device-test",
        runtimeVersion = "1",
        modelId = "history-test",
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
    var documentCalls: Int = 0

    override suspend fun availability(): MemoryEmbeddingAvailability = MemoryEmbeddingAvailability.Available

    override suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>> {
        documentCalls++
        return Result.success(texts.map(::embed))
    }

    override suspend fun embedQuery(text: String): Result<FloatArray> = Result.success(embed(text))

    private fun embed(text: String): FloatArray = if (text.contains("项目")) {
        floatArrayOf(1f, 0f, 0f, 0f)
    } else {
        floatArrayOf(0f, 1f, 0f, 0f)
    }
}

private fun descriptorHash(provider: MemoryEmbeddingProvider): String {
    val descriptor = provider.descriptor
    val value = listOf(
        descriptor.providerId,
        descriptor.runtimeVersion,
        descriptor.modelId,
        descriptor.modelVersion,
        descriptor.modelSha256,
        descriptor.dimension.toString(),
        descriptor.tokenizerVersion,
        descriptor.tokenizerFingerprint,
        descriptor.pooling.name,
        descriptor.queryPrefix,
        descriptor.documentPrefix
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
