package dev.chungjungsoo.gptmobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchScheduler
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImpl
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopOrchestrator
import dev.chungjungsoo.gptmobile.data.websearch.SearchDecisionService
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
        chatRoomV2Dao: ChatRoomV2Dao,
        messageV2Dao: MessageV2Dao,
        chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
        settingRepository: SettingRepository,
        openAIAPI: OpenAIAPI,
        groqAPI: GroqAPI,
        anthropicAPI: AnthropicAPI,
        googleAPI: GoogleAPI,
        attachmentUploadCoordinator: dev.chungjungsoo.gptmobile.data.repository.AttachmentUploadCoordinator,
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
        memoryTurnBatchScheduler = memoryTurnBatchScheduler
    )
}
