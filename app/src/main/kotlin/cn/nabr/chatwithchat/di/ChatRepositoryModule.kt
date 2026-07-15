package cn.nabr.chatwithchat.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import cn.nabr.chatwithchat.data.context.ContextBuilder
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.ChatPlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomDao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomV2Dao
import cn.nabr.chatwithchat.data.database.dao.MessageDao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.GroqAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.repository.ChatRepository
import cn.nabr.chatwithchat.data.repository.ChatRepositoryImpl
import cn.nabr.chatwithchat.data.repository.SettingRepository
import cn.nabr.chatwithchat.data.tool.ToolLoopOrchestrator
import cn.nabr.chatwithchat.data.websearch.SearchDecisionService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatRepositoryModule {

    @Provides
    @Singleton
    fun provideChatRepository(
        @ApplicationContext context: Context,
        chatRoomDao: ChatRoomDao,
        messageDao: MessageDao,
        chatDatabaseV2: ChatDatabaseV2,
        chatRoomV2Dao: ChatRoomV2Dao,
        messageV2Dao: MessageV2Dao,
        chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
        settingRepository: SettingRepository,
        openAIAPI: OpenAIAPI,
        groqAPI: GroqAPI,
        anthropicAPI: AnthropicAPI,
        googleAPI: GoogleAPI,
        attachmentUploadCoordinator: cn.nabr.chatwithchat.data.repository.AttachmentUploadCoordinator,
        contextBuilder: ContextBuilder,
        toolLoopOrchestrator: ToolLoopOrchestrator,
        searchDecisionService: SearchDecisionService,
        memoryTurnBatchScheduler: MemoryTurnBatchScheduler
    ): ChatRepository = ChatRepositoryImpl(
        context,
        chatRoomDao,
        messageDao,
        chatRoomV2Dao,
        messageV2Dao,
        chatPlatformModelV2Dao,
        settingRepository,
        openAIAPI,
        groqAPI,
        anthropicAPI,
        googleAPI,
        attachmentUploadCoordinator,
        contextBuilder,
        toolLoopOrchestrator,
        searchDecisionService = searchDecisionService,
        memoryTurnBatchScheduler = memoryTurnBatchScheduler,
        chatDatabaseV2 = chatDatabaseV2
    )
}
