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
import cn.nabr.chatwithchat.data.dto.Platform
import cn.nabr.chatwithchat.data.dto.ThemeSetting
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.memory.MemoryModelPreference
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.LastSelectedModel
import cn.nabr.chatwithchat.data.model.ModelRefreshResult
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.tool.ToolCallingMode
import cn.nabr.chatwithchat.data.tool.ToolEnablementOverrides
import cn.nabr.chatwithchat.data.websearch.WebSearchMode
import cn.nabr.chatwithchat.data.repository.SettingRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryLexicalInstrumentedTest {
    private lateinit var database: ChatDatabaseV2

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_lexical_${System.nanoTime()}"
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
    fun chineseShortQueryUsesFtsAndExcludesCurrentAndStaleChats() = runBlocking {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "旧项目", enabledPlatform = emptyList()))
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 2, title = "当前聊天", enabledPlatform = emptyList()))
        val source = projection(1, "项目管理", "项目管理已经完成排期。", HistoryEligibilityState.ELIGIBLE)
        val current = projection(2, "项目管理", "当前聊天不应重复。", HistoryEligibilityState.ELIGIBLE)
        val stale = projection(1, "项目管理", "旧的 stale 内容。", HistoryEligibilityState.STALE, userMessageId = 2)
        database.chatHistoryDao().upsertProjection(source)
        database.chatHistoryDao().upsertProjection(current)
        database.chatHistoryDao().upsertProjection(stale)

        val retriever = ChatHistoryRetriever(database.chatHistoryDao(), FakeSettingRepository(true))
        val report = retriever.retrieve(
            HistoryRetrievalRequest(
                query = "项目管理",
                currentChatId = 2,
                limit = 4
            )
        )

        assertEquals(HistoryRecallMode.LEXICAL, report.snapshot.mode)
        assertEquals(1, report.snapshot.snippets.size)
        assertEquals(1, report.snapshot.snippets.single().chatId)
        assertTrue(report.snapshot.prompt.orEmpty().contains("项目管理已经完成排期"))
        assertTrue(report.snapshot.prompt.orEmpty().contains("不应重复").not())
        assertTrue(report.snapshot.prompt.orEmpty().contains("旧的 stale 内容").not())
    }

    @Test
    fun fts4ProjectionTriggersHandleInsertUpdateDeleteAndRebuild() = runBlocking {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 3, title = "FTS lifecycle", enabledPlatform = emptyList()))
        val original = projection(
            chatId = 3,
            userContent = "release runbook",
            assistantContent = "signing evidence",
            state = HistoryEligibilityState.ELIGIBLE
        )
        database.chatHistoryDao().upsertProjection(original)
        val retriever = ChatHistoryRetriever(database.chatHistoryDao(), FakeSettingRepository(true))

        assertEquals(
            HistoryRecallMode.LEXICAL,
            retriever.retrieve(HistoryRetrievalRequest("release runbook", currentChatId = 99)).snapshot.mode
        )

        val updated = original.copy(
            userContent = "rollback checklist",
            assistantContent = "rollback evidence",
            searchTerms = ChatHistoryQueryNormalizer.indexTerms("rollback checklist rollback evidence").joinToString(" "),
            contentHash = "hash-3-updated"
        )
        database.chatHistoryDao().upsertProjection(updated)
        assertEquals(
            HistoryRecallMode.NONE,
            retriever.retrieve(HistoryRetrievalRequest("release runbook", currentChatId = 99)).snapshot.mode
        )
        assertEquals(
            HistoryRecallMode.LEXICAL,
            retriever.retrieve(HistoryRetrievalRequest("rollback checklist", currentChatId = 99)).snapshot.mode
        )

        database.chatHistoryDao().deleteProjectionByKey(updated.turnKey)
        assertEquals(
            HistoryRecallMode.NONE,
            retriever.retrieve(HistoryRetrievalRequest("rollback checklist", currentChatId = 99)).snapshot.mode
        )

        database.chatHistoryDao().upsertProjection(original)
        ChatDatabaseV2Migrations.rebuildChatHistoryFts(database.openHelper.writableDatabase)
        assertEquals(
            HistoryRecallMode.LEXICAL,
            retriever.retrieve(HistoryRetrievalRequest("release runbook", currentChatId = 99)).snapshot.mode
        )
    }

    private fun projection(
        chatId: Int,
        userContent: String,
        assistantContent: String,
        state: String,
        userMessageId: Int = 1
    ): ChatHistoryProjectionEntity {
        val terms = ChatHistoryQueryNormalizer.indexTerms("项目管理 $userContent $assistantContent")
        return ChatHistoryProjectionEntity(
            turnKey = "chat:$chatId:user:$userMessageId",
            chatId = chatId,
            userMessageId = userMessageId,
            assistantMessageId = userMessageId + 10,
            assistantPlatformUid = "provider",
            title = "项目管理",
            userContent = userContent,
            assistantContent = assistantContent,
            searchTerms = terms.joinToString(" "),
            contentHash = "hash-$chatId-$userMessageId",
            projectionVersion = CURRENT_PROJECTION_VERSION,
            eligibilityState = state,
            createdAt = 1,
            updatedAt = 1
        )
    }
}

internal class FakeSettingRepository(
    private var enabled: Boolean
) : SettingRepository {
    override suspend fun fetchPlatforms(): List<Platform> = emptyList()
    override suspend fun fetchPlatformV2s(): List<PlatformV2> = emptyList()
    override suspend fun fetchPlatformModels(): List<PlatformModelV2> = emptyList()
    override suspend fun fetchPlatformModels(platformUid: String): List<PlatformModelV2> = emptyList()
    override suspend fun fetchEnabledChatModels(): List<AvailableChatModel> = emptyList()
    override suspend fun resolveDefaultChatModel(): AvailableChatModel? = null
    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()
    override suspend fun fetchLastSelectedModel(): LastSelectedModel? = null
    override suspend fun fetchMemoryEnabled(): Boolean = enabled
    override suspend fun fetchMemoryModelPreference(): MemoryModelPreference = MemoryModelPreference.Auto
    override suspend fun fetchMemoryMaintenanceNotificationsEnabled(): Boolean = false
    override suspend fun fetchToolCallingMode(): ToolCallingMode = ToolCallingMode.Auto
    override suspend fun fetchDisabledToolNames(): Set<String> = emptySet()
    override suspend fun fetchWebSearchMode(): WebSearchMode = WebSearchMode.Off
    override suspend fun fetchWebSearchSearxngBaseUrl(): String = ""
    override suspend fun migrateToPlatformV2() = Unit
    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit
    override suspend fun updateLastSelectedModel(platformUid: String, model: String, reasoningMode: ReasoningMode) = Unit
    override suspend fun updateMemoryEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
    override suspend fun updateMemoryModelPreference(preference: MemoryModelPreference) = Unit
    override suspend fun updateMemoryMaintenanceNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun updateToolCallingMode(mode: ToolCallingMode) = Unit
    override suspend fun updateToolEnabled(toolName: String, enabled: Boolean) = Unit
    override suspend fun updateWebSearchMode(mode: WebSearchMode) = Unit
    override suspend fun updateWebSearchSearxngBaseUrl(baseUrl: String) = Unit
    override suspend fun refreshPlatformModels(platformUid: String): ModelRefreshResult = error("not used")
    override suspend fun updatePlatformModelEnabled(platformUid: String, modelId: String, enabled: Boolean) = Unit
    override suspend fun setPlatformDefaultModel(platformUid: String, modelId: String) = Unit
    override suspend fun addPlatformV2(platform: PlatformV2) = Unit
    override suspend fun updatePlatformV2(platform: PlatformV2) = Unit
    override suspend fun deletePlatformV2(platform: PlatformV2) = Unit
    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = null
}
