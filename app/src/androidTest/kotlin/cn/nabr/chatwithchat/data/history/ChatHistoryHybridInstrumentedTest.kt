package cn.nabr.chatwithchat.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2Migrations
import cn.nabr.chatwithchat.data.database.ChatHistoryDatabaseCallback
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryHybridInstrumentedTest {
    private lateinit var database: ChatDatabaseV2

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_hybrid_${System.nanoTime()}"
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun hybridPathRejectsVectorHardNegativesAndCollapsesDuplicateChats() = runBlocking {
        listOf(80, 81, 82, 83).forEach { chatId ->
            database.chatRoomDao().addChatRoom(
                ChatRoomV2(id = chatId, title = "Chat $chatId", enabledPlatform = emptyList())
            )
        }
        val duplicateUser = "project schedule"
        val duplicateAssistant = "the project schedule is ready"
        database.chatHistoryDao().upsertProjection(projection(80, duplicateUser, duplicateAssistant))
        database.chatHistoryDao().upsertProjection(projection(81, duplicateUser, duplicateAssistant))
        database.chatHistoryDao().upsertProjection(projection(82, duplicateUser, "current chat answer"))
        database.chatHistoryDao().upsertProjection(projection(83, "cooking recipe", "unrelated answer"))

        val vectorStore = StubVectorStore(
            listOf(
                HistoryVectorCandidate("chat:80:user:1", 0.91f),
                HistoryVectorCandidate("chat:81:user:1", 0.88f),
                HistoryVectorCandidate("chat:82:user:1", 0.99f),
                HistoryVectorCandidate("chat:83:user:1", 0.54f)
            )
        )
        val report = ChatHistoryRetriever(
            historyDao = database.chatHistoryDao(),
            settingRepository = FakeSettingRepository(true),
            vectorStore = vectorStore
        ).retrieve(
            HistoryRetrievalRequest(
                query = duplicateUser,
                currentChatId = 82,
                limit = 8
            )
        )

        assertEquals(HistoryRecallMode.HYBRID, report.snapshot.mode)
        assertEquals(1, report.snapshot.snippets.size)
        assertEquals(80, report.snapshot.snippets.single().chatId)
        assertFalse(report.snapshot.snippets.any { it.chatId == 82 || it.chatId == 83 })
        assertEquals(2, report.lexicalCandidateCount)
        assertEquals(2, report.vectorCandidateCount)
    }

    private fun projection(chatId: Int, user: String, assistant: String): ChatHistoryProjectionEntity {
        return ChatHistoryProjectionEntity(
            turnKey = "chat:$chatId:user:1",
            chatId = chatId,
            userMessageId = 1,
            assistantMessageId = 2,
            assistantPlatformUid = "provider",
            title = "Chat $chatId",
            userContent = user,
            assistantContent = assistant,
            searchTerms = ChatHistoryQueryNormalizer.indexTerms("$user $assistant").joinToString(" "),
            contentHash = "hash-$chatId",
            projectionVersion = CURRENT_PROJECTION_VERSION,
            eligibilityState = HistoryEligibilityState.ELIGIBLE,
            createdAt = 1,
            updatedAt = 1
        )
    }
}

private class StubVectorStore(
    private val candidates: List<HistoryVectorCandidate>
) : HistoryVectorStore {
    override suspend fun publish(
        projections: List<ChatHistoryProjectionEntity>
    ): Result<HistoryVectorPublishResult> = Result.failure(IllegalStateException("not used"))

    override suspend fun search(query: String, limit: Int): List<HistoryVectorCandidate> = candidates
}
