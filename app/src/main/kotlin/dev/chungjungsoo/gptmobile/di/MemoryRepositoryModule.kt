package dev.chungjungsoo.gptmobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.database.dao.ChatClassificationDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryIndexDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.memory.LlmMemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryCodec
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryDebugEditor
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryLearningService
import dev.chungjungsoo.gptmobile.data.memory.MemoryBatchConsolidationService
import dev.chungjungsoo.gptmobile.data.memory.MemoryChunker
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRepository
import dev.chungjungsoo.gptmobile.data.memory.MemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceEventSink
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceNotificationEventSink
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceNotificationPolicy
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkEnqueuer
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMarkdownCodec
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchCoordinator
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchScheduler
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepositoryImpl
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemoryRepositoryModule {

    @Provides
    @Singleton
    fun provideMemoryPromptBuilder(): MemoryPromptBuilder = MemoryPromptBuilder()

    @Provides
    @Singleton
    fun provideMemoryMarkdownCodec(): MemoryMarkdownCodec = MemoryMarkdownCodec()

    @Provides
    @Singleton
    fun provideMarkdownMemoryCodec(): MarkdownMemoryCodec = MarkdownMemoryCodec()

    @Provides
    @Singleton
    fun provideMemoryFilePaths(
        @ApplicationContext context: Context
    ): MemoryFilePaths = MemoryFilePaths.fromContext(context)

    @Provides
    @Singleton
    fun provideMemoryFileStore(memoryFilePaths: MemoryFilePaths): MemoryFileStore =
        MemoryFileStore(memoryFilePaths)

    @Provides
    @Singleton
    fun provideMemoryChunker(): MemoryChunker = MemoryChunker()

    @Provides
    @Singleton
    fun provideMemoryIndexRepository(
        memoryFileStore: MemoryFileStore,
        memoryIndexDao: MemoryIndexDao,
        memoryChunker: MemoryChunker
    ): MemoryIndexRepository = MemoryIndexRepository(
        memoryFileStore = memoryFileStore,
        memoryIndexDao = memoryIndexDao,
        memoryChunker = memoryChunker
    )

    @Provides
    @Singleton
    internal fun provideMarkdownMemoryDebugEditor(
        memoryFileStore: MemoryFileStore,
        markdownMemoryCodec: MarkdownMemoryCodec,
        memoryIndexRepository: MemoryIndexRepository
    ): MarkdownMemoryDebugEditor = MarkdownMemoryDebugEditor(
        memoryFileStore = memoryFileStore,
        markdownMemoryCodec = markdownMemoryCodec,
        memoryIndexRebuilder = memoryIndexRepository
    )

    @Provides
    @Singleton
    fun provideMemoryMaintenanceNotificationPolicy(): MemoryMaintenanceNotificationPolicy =
        MemoryMaintenanceNotificationPolicy()

    @Provides
    @Singleton
    fun provideMemoryMaintenanceEventSink(
        notificationEventSink: MemoryMaintenanceNotificationEventSink
    ): MemoryMaintenanceEventSink = notificationEventSink

    @Provides
    @Singleton
    fun provideMemoryMaintenanceScheduler(
        memoryMaintenanceJobDao: MemoryMaintenanceJobDao,
        memoryMaintenanceEventSink: MemoryMaintenanceEventSink
    ): MemoryMaintenanceScheduler = MemoryMaintenanceScheduler(
        jobDao = memoryMaintenanceJobDao,
        eventSink = memoryMaintenanceEventSink
    )

    @Provides
    @Singleton
    fun provideMemoryMaintenanceWorkEnqueuer(
        memoryMaintenanceWorkScheduler: MemoryMaintenanceWorkScheduler
    ): MemoryMaintenanceWorkEnqueuer = memoryMaintenanceWorkScheduler

    @Provides
    @Singleton
    fun provideMemoryTurnBatchCoordinator(
        memoryTurnBatchDao: MemoryTurnBatchDao,
        memoryTurnBatchScheduler: MemoryTurnBatchScheduler
    ): MemoryTurnBatchCoordinator = MemoryTurnBatchCoordinator(
        turnBatchDao = memoryTurnBatchDao,
        pendingTurnObserver = memoryTurnBatchScheduler
    )

    @Provides
    @Singleton
    fun provideMemoryTurnBatchScheduler(
        memoryTurnBatchDao: MemoryTurnBatchDao,
        memoryMaintenanceJobDao: MemoryMaintenanceJobDao,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        memoryMaintenanceWorkScheduler: MemoryMaintenanceWorkEnqueuer,
        settingRepository: SettingRepository
    ): MemoryTurnBatchScheduler = MemoryTurnBatchScheduler(
        turnBatchDao = memoryTurnBatchDao,
        maintenanceJobDao = memoryMaintenanceJobDao,
        maintenanceScheduler = memoryMaintenanceScheduler,
        workEnqueuer = memoryMaintenanceWorkScheduler,
        settingRepository = settingRepository
    )

    @Provides
    @Singleton
    fun provideMemoryBatchConsolidationService(
        memoryTurnBatchDao: MemoryTurnBatchDao,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        memoryTurnBatchScheduler: MemoryTurnBatchScheduler,
        settingRepository: SettingRepository,
        memoryIntelligence: MemoryIntelligence,
        memoryFileStore: MemoryFileStore,
        markdownMemoryCodec: MarkdownMemoryCodec,
        memoryIndexRepository: MemoryIndexRepository
    ): MemoryBatchConsolidationService = MemoryBatchConsolidationService(
        turnBatchDao = memoryTurnBatchDao,
        maintenanceScheduler = memoryMaintenanceScheduler,
        turnBatchScheduler = memoryTurnBatchScheduler,
        settingRepository = settingRepository,
        memoryIntelligence = memoryIntelligence,
        memoryFileStore = memoryFileStore,
        markdownMemoryCodec = markdownMemoryCodec,
        memoryRetriever = memoryIndexRepository,
        memoryIndexRebuilder = memoryIndexRepository
    )

    @Provides
    @Singleton
    fun provideMarkdownMemoryLearningService(
        memoryFileStore: MemoryFileStore,
        markdownMemoryCodec: MarkdownMemoryCodec,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        memoryIndexRepository: MemoryIndexRepository
    ): MarkdownMemoryLearningService = MarkdownMemoryLearningService(
        memoryFileStore = memoryFileStore,
        markdownMemoryCodec = markdownMemoryCodec,
        maintenanceScheduler = memoryMaintenanceScheduler,
        memoryIndexRebuilder = memoryIndexRepository
    )

    @Provides
    @Singleton
    fun provideMemoryIntelligence(
        settingRepository: SettingRepository,
        openAIAPI: OpenAIAPI,
        anthropicAPI: AnthropicAPI,
        googleAPI: GoogleAPI
    ): MemoryIntelligence = LlmMemoryIntelligence(settingRepository, openAIAPI, anthropicAPI, googleAPI)

    @Provides
    @Singleton
    fun provideMemoryRepository(
        personalMemoryDao: PersonalMemoryDao,
        chatClassificationDao: ChatClassificationDao,
        memoryIntelligence: MemoryIntelligence,
        memoryPromptBuilder: MemoryPromptBuilder,
        memoryMarkdownCodec: MemoryMarkdownCodec,
        memoryIndexRepository: MemoryIndexRepository,
        markdownMemoryLearningService: MarkdownMemoryLearningService,
        memoryFileStore: MemoryFileStore,
        markdownMemoryCodec: MarkdownMemoryCodec,
        memoryMaintenanceJobDao: MemoryMaintenanceJobDao,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        memoryMaintenanceWorkScheduler: MemoryMaintenanceWorkEnqueuer,
        memoryTurnBatchCoordinator: MemoryTurnBatchCoordinator,
        memoryTurnBatchScheduler: MemoryTurnBatchScheduler
    ): MemoryRepository = MemoryRepositoryImpl(
        personalMemoryDao = personalMemoryDao,
        chatClassificationDao = chatClassificationDao,
        memoryIntelligence = memoryIntelligence,
        memoryPromptBuilder = memoryPromptBuilder,
        memoryMarkdownCodec = memoryMarkdownCodec,
        memoryRetriever = memoryIndexRepository,
        markdownMemoryLearningService = markdownMemoryLearningService,
        memoryFileStore = memoryFileStore,
        structuredMarkdownMemoryCodec = markdownMemoryCodec,
        memoryIndexRebuilder = memoryIndexRepository,
        memoryMaintenanceJobDao = memoryMaintenanceJobDao,
        memoryMaintenanceScheduler = memoryMaintenanceScheduler,
        memoryMaintenanceWorkScheduler = memoryMaintenanceWorkScheduler,
        memoryTurnBatchCoordinator = memoryTurnBatchCoordinator,
        memoryTurnBatchScheduler = memoryTurnBatchScheduler
    )
}
