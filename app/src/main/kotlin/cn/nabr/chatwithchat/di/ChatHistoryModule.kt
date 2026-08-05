package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.history.ChatHistoryProjectionBuilder
import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.history.ChatHistoryPromptBuilder
import cn.nabr.chatwithchat.data.history.ChatHistoryRetriever
import cn.nabr.chatwithchat.data.history.HistoryVectorStore
import cn.nabr.chatwithchat.data.history.RoomHistoryVectorStore
import cn.nabr.chatwithchat.data.history.ChatHistoryWorkScheduler
import cn.nabr.chatwithchat.data.history.WorkManagerChatHistoryWorkScheduler
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.repository.SettingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatHistoryModule {
    @Provides
    @Singleton
    fun provideChatHistoryProjectionBuilder(): ChatHistoryProjectionBuilder = ChatHistoryProjectionBuilder()

    @Provides
    @Singleton
    fun provideChatHistoryPromptBuilder(): ChatHistoryPromptBuilder = ChatHistoryPromptBuilder()

    @Provides
    @Singleton
    fun provideChatHistoryWorkScheduler(
        scheduler: WorkManagerChatHistoryWorkScheduler
    ): ChatHistoryWorkScheduler = scheduler

    @Provides
    @Singleton
    fun provideHistoryVectorStore(
        database: ChatDatabaseV2,
        historyDao: ChatHistoryDao,
        capabilitySource: MemoryEmbeddingCapabilitySource
    ): HistoryVectorStore = RoomHistoryVectorStore(database, historyDao, capabilitySource)

    @Provides
    @Singleton
    fun provideChatHistoryRetriever(
        historyDao: ChatHistoryDao,
        settingRepository: SettingRepository,
        promptBuilder: ChatHistoryPromptBuilder,
        vectorStore: HistoryVectorStore
    ): ChatHistoryRetriever = ChatHistoryRetriever(historyDao, settingRepository, promptBuilder, vectorStore)

}
