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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryRelevanceInstrumentedTest {
    private lateinit var database: ChatDatabaseV2

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_relevance_${System.nanoTime()}"
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
    fun fixedFixtureCoversExactCjkParaphraseNegativesDeduplicationAndInvalidation() = runBlocking {
        val rooms = (101..108).map { id ->
            ChatRoomV2(id = id, title = "History fixture $id", enabledPlatform = emptyList())
        }
        rooms.forEach { room -> database.chatRoomDao().addChatRoom(room) }

        val cjkProject = "\u9879\u76ee\u6392\u671f"
        val longAnswer = buildString {
            append("The first release sentence is intentionally retained. ")
            repeat(40) { append("Additional historical detail $it. ") }
            append("TAIL_SHOULD_NOT_BE_PACKED")
        }
        database.chatHistoryDao().upsertProjection(
            projection(101, "Apollo release checklist", "Signing and rollback evidence are required.")
        )
        database.chatHistoryDao().upsertProjection(
            projection(102, "$cjkProject\u5df2\u7ecf\u5b8c\u6210", "\u53d1\u5e03\u65e5\u671f\u5df2\u786e\u8ba4\u3002")
        )
        database.chatHistoryDao().upsertProjection(
            projection(103, "How do I ship the mobile build?", "Use the signed release workflow.")
        )
        database.chatHistoryDao().upsertProjection(
            projection(104, "Apollo release checklist", "Signing and rollback evidence are required.")
        )
        database.chatHistoryDao().upsertProjection(
            projection(105, "Current chat private note", "This must be excluded from its own recall.")
        )
        database.chatHistoryDao().upsertProjection(
            projection(106, "stale-only phrase", "This stale answer must never be injected.", HistoryEligibilityState.STALE)
        )
        database.chatHistoryDao().upsertProjection(
            projection(107, "deleted-only phrase", "This deleted answer must never be injected.")
        )
        database.chatHistoryDao().upsertProjection(
            projection(108, "truncation marker", longAnswer)
        )

        val lexicalRetriever = ChatHistoryRetriever(
            historyDao = database.chatHistoryDao(),
            settingRepository = FakeSettingRepository(true)
        )
        val exact = lexicalRetriever.retrieve(
            HistoryRetrievalRequest("Apollo release checklist", currentChatId = 999)
        ).snapshot
        assertEquals(HistoryRecallMode.LEXICAL, exact.mode)
        assertEquals(listOf(101), exact.snippets.map { it.chatId })

        val cjk = lexicalRetriever.retrieve(
            HistoryRetrievalRequest(cjkProject, currentChatId = 999)
        ).snapshot
        assertEquals(HistoryRecallMode.LEXICAL, cjk.mode)
        assertEquals(listOf(102), cjk.snippets.map { it.chatId })

        val paraphrase = ChatHistoryRetriever(
            historyDao = database.chatHistoryDao(),
            settingRepository = FakeSettingRepository(true),
            vectorStore = MappingVectorStore(
                turnKey = "chat:103:user:1",
                score = 0.91f
            )
        ).retrieve(
            HistoryRetrievalRequest("deploy the application", currentChatId = 999)
        ).snapshot
        assertEquals(HistoryRecallMode.SEMANTIC, paraphrase.mode)
        assertEquals(listOf(103), paraphrase.snippets.map { it.chatId })

        val unrelated = lexicalRetriever.retrieve(
            HistoryRetrievalRequest("banana recipe", currentChatId = 999)
        ).snapshot
        assertEquals(HistoryRecallMode.NONE, unrelated.mode)
        assertTrue(unrelated.snippets.isEmpty())

        val sameChat = lexicalRetriever.retrieve(
            HistoryRetrievalRequest("Current chat private note", currentChatId = 105)
        ).snapshot
        assertEquals(HistoryRecallMode.NONE, sameChat.mode)

        val stale = lexicalRetriever.retrieve(
            HistoryRetrievalRequest("stale-only phrase", currentChatId = 999)
        ).snapshot
        assertEquals(emptyList<Int>(), stale.snippets.map { it.chatId })
        assertEquals(HistoryRecallMode.NONE, stale.mode)

        database.chatRoomDao().deleteChatRooms(rooms.first { it.id == 107 })
        assertTrue(database.chatHistoryDao().findProjection("chat:107:user:1") == null)
        val deleted = lexicalRetriever.retrieve(
            HistoryRetrievalRequest("deleted-only phrase", currentChatId = 999)
        ).snapshot
        assertEquals(HistoryRecallMode.NONE, deleted.mode)

        val packed = lexicalRetriever.retrieve(
            HistoryRetrievalRequest(
                query = "truncation marker",
                currentChatId = 999,
                tokenBudget = 48
            )
        ).snapshot
        assertEquals(HistoryRecallMode.LEXICAL, packed.mode)
        assertTrue(packed.estimatedTokens <= 48)
        assertTrue(packed.prompt.orEmpty().contains("first release sentence"))
        assertFalse(packed.prompt.orEmpty().contains("TAIL_SHOULD_NOT_BE_PACKED"))
    }

    private fun projection(
        chatId: Int,
        userContent: String,
        assistantContent: String,
        state: String = HistoryEligibilityState.ELIGIBLE
    ): ChatHistoryProjectionEntity {
        val searchTerms = ChatHistoryQueryNormalizer
            .indexTerms("History fixture $chatId $userContent $assistantContent")
            .joinToString(" ")
        return ChatHistoryProjectionEntity(
            turnKey = "chat:$chatId:user:1",
            chatId = chatId,
            userMessageId = 1,
            assistantMessageId = 2,
            assistantPlatformUid = "provider",
            title = "History fixture $chatId",
            userContent = userContent,
            assistantContent = assistantContent,
            searchTerms = searchTerms,
            contentHash = "hash-$chatId",
            projectionVersion = CURRENT_PROJECTION_VERSION,
            eligibilityState = state,
            createdAt = 1,
            updatedAt = 1
        )
    }
}

private class MappingVectorStore(
    private val turnKey: String,
    private val score: Float
) : HistoryVectorStore {
    override suspend fun publish(
        projections: List<ChatHistoryProjectionEntity>
    ): Result<HistoryVectorPublishResult> = Result.failure(IllegalStateException("not used"))

    override suspend fun search(query: String, limit: Int): List<HistoryVectorCandidate> =
        if (query.contains("deploy", ignoreCase = true)) {
            listOf(HistoryVectorCandidate(turnKey, score))
        } else {
            emptyList()
        }
}
