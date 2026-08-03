package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.database.ChatDatabaseV2
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomV2Dao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.history.ChatHistoryIndexCoordinator
import cn.nabr.chatwithchat.data.history.ChatHistoryIndexProcessor
import cn.nabr.chatwithchat.data.history.ChatHistoryProjectionBuilder
import cn.nabr.chatwithchat.data.history.ChatHistoryRetriever
import cn.nabr.chatwithchat.data.history.ChatHistoryWorkEnqueuer
import cn.nabr.chatwithchat.data.history.ChatHistoryWorkScheduler
import cn.nabr.chatwithchat.data.history.HistoryVectorStore
import cn.nabr.chatwithchat.data.history.RoomHistoryVectorStore
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
    fun provideChatHistoryWorkEnqueuer(scheduler: ChatHistoryWorkScheduler): ChatHistoryWorkEnqueuer = scheduler

    @Provides
    @Singleton
    fun provideHistoryVectorStore(
        historyDao: ChatHistoryDao,
        capabilitySource: MemoryEmbeddingCapabilitySource
    ): HistoryVectorStore = RoomHistoryVectorStore(historyDao, capabilitySource)

    @Provides
    @Singleton
    fun provideChatHistoryRetriever(
        historyDao: ChatHistoryDao,
        settingRepository: SettingRepository,
        historyVectorStore: HistoryVectorStore
    ): ChatHistoryRetriever = ChatHistoryRetriever(historyDao, settingRepository, historyVectorStore)

    @Provides
    @Singleton
    fun provideChatHistoryIndexCoordinator(
        historyDao: ChatHistoryDao,
        chatRoomDao: ChatRoomV2Dao,
        messageDao: MessageV2Dao,
        settingRepository: SettingRepository,
        workEnqueuer: ChatHistoryWorkEnqueuer
    ): ChatHistoryIndexCoordinator = ChatHistoryIndexCoordinator(
        historyDao,
        chatRoomDao,
        messageDao,
        settingRepository,
        workEnqueuer
    )

    @Provides
    @Singleton
    fun provideChatHistoryIndexProcessor(
        database: ChatDatabaseV2,
        historyDao: ChatHistoryDao,
        chatRoomDao: ChatRoomV2Dao,
        messageDao: MessageV2Dao,
        settingRepository: SettingRepository,
        projectionBuilder: ChatHistoryProjectionBuilder,
        vectorStore: HistoryVectorStore
    ): ChatHistoryIndexProcessor = ChatHistoryIndexProcessor(
        database,
        historyDao,
        chatRoomDao,
        messageDao,
        settingRepository,
        projectionBuilder,
        vectorStore
    )
}
