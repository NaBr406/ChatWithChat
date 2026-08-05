package cn.nabr.chatwithchat.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2Migrations
import cn.nabr.chatwithchat.data.database.ChatHistoryDatabaseCallback
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.dto.openai.common.Role
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ChatMessage
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.network.OpenAIAPIImpl
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryProviderDeviceInstrumentedTest {
    private lateinit var database: ChatDatabaseV2

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "history_provider_${System.nanoTime()}"
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
    fun realProviderReceivesBoundedHistoryPromptAndReturnsAResponse() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("deepseek_api_key").orEmpty()
        assumeTrue("deepseek_api_key instrumentation argument is required", token.isNotBlank())
        val apiUrl = args.getString("deepseek_api_url").orEmpty().ifBlank { "https://api.deepseek.com" }
        val model = args.getString("deepseek_model").orEmpty().ifBlank { "deepseek-chat" }

        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 501, title = "Provider history", enabledPlatform = emptyList()))
        database.chatHistoryDao().upsertProjection(
            ChatHistoryProjectionEntity(
                turnKey = "chat:501:user:1",
                chatId = 501,
                userMessageId = 1,
                assistantMessageId = 2,
                assistantPlatformUid = "provider",
                title = "Provider history",
                userContent = "What is the release checklist?",
                assistantContent = "The release checklist includes signing, migration verification, and rollback evidence.",
                searchTerms = ChatHistoryQueryNormalizer
                    .indexTerms("release checklist signing migration rollback")
                    .joinToString(" "),
                contentHash = "provider-history-hash",
                projectionVersion = CURRENT_PROJECTION_VERSION,
                eligibilityState = HistoryEligibilityState.ELIGIBLE,
                createdAt = 1,
                updatedAt = 1
            )
        )
        val history = ChatHistoryRetriever(
            historyDao = database.chatHistoryDao(),
            settingRepository = FakeSettingRepository(true)
        ).retrieve(
            HistoryRetrievalRequest(
                query = "release checklist",
                currentChatId = 999,
                limit = 4
            )
        ).snapshot
        assertEquals(HistoryRecallMode.LEXICAL, history.mode)
        assertTrue(history.prompt.orEmpty().contains("signing"))
        assertFalse(history.prompt.orEmpty().contains("chat:501:user:1"))

        val api = OpenAIAPIImpl(NetworkClient(CIO))
        api.setToken(token)
        api.setAPIUrl(apiUrl)
        val responseChunks = api.streamChatCompletion(
            ChatCompletionRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = Role.SYSTEM, contentText = history.prompt),
                    ChatMessage(
                        role = Role.USER,
                        contentText = "Using the quoted historical evidence, summarize the release checklist in one short sentence."
                    )
                ),
                stream = true,
                maxTokens = 128
            ),
            timeoutSeconds = 90
        ).toList()
        val error = responseChunks.firstNotNullOfOrNull { it.error }
        assertTrue("provider returned an error: ${error?.message}", error == null)
        val responseText = responseChunks
            .flatMap { it.choices.orEmpty() }
            .mapNotNull { it.delta.content }
            .joinToString("")
        assertTrue("provider returned no visible response", responseText.isNotBlank())
    }
}
