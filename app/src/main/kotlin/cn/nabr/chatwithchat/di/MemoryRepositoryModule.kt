package cn.nabr.chatwithchat.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.dao.MemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.database.dao.MemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.memory.HybridMemoryRetriever
import cn.nabr.chatwithchat.data.memory.LlmMemoryIntelligence
import cn.nabr.chatwithchat.data.memory.MarkdownLexicalRetriever
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryCodec
import cn.nabr.chatwithchat.data.memory.MemoryActivityLogger
import cn.nabr.chatwithchat.data.memory.MemoryBatchConsolidationService
import cn.nabr.chatwithchat.data.memory.MemoryChunker
import cn.nabr.chatwithchat.data.memory.MemoryCorpusSnapshotter
import cn.nabr.chatwithchat.data.memory.MemoryDailyDistillationOperationController
import cn.nabr.chatwithchat.data.memory.MemoryDailyDistillationRecoveryFinalizer
import cn.nabr.chatwithchat.data.memory.MemoryDailyDistillationScheduler
import cn.nabr.chatwithchat.data.memory.MemoryDailyDistillationService
import cn.nabr.chatwithchat.data.memory.MemoryFilePaths
import cn.nabr.chatwithchat.data.memory.MemoryFileStore
import cn.nabr.chatwithchat.data.memory.MemoryIndexSyncService
import cn.nabr.chatwithchat.data.memory.MemoryIndexSynchronizer
import cn.nabr.chatwithchat.data.memory.MemoryIntelligence
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceCorpusReader
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceEventSink
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceLeaseWatchdog
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceNotificationEventSink
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceNotificationPolicy
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkEnqueuer
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkScheduler
import cn.nabr.chatwithchat.data.memory.MemoryMutationCoordinator
import cn.nabr.chatwithchat.data.memory.MemoryMutationRecoveryService
import cn.nabr.chatwithchat.data.memory.MemoryPromptBuilder
import cn.nabr.chatwithchat.data.memory.MemoryRetriever
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchCoordinator
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.memory.MemoryVectorIndexBootstrapService
import cn.nabr.chatwithchat.data.memory.MemoryVectorIndexRecoveryService
import cn.nabr.chatwithchat.data.memory.MemoryVectorRecallRepairTrigger
import cn.nabr.chatwithchat.data.memory.MemoryVectorRecallStateSource
import cn.nabr.chatwithchat.data.memory.RoomMemoryActivityLogger
import cn.nabr.chatwithchat.data.memory.RoomMemoryVectorRecallStateSource
import cn.nabr.chatwithchat.data.memory.WorkEnqueuingMemoryVectorRecallRepairTrigger
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingArtifactInstaller
import cn.nabr.chatwithchat.data.memory.embedding.MemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.memory.embedding.MutableMemoryEmbeddingCapabilitySource
import cn.nabr.chatwithchat.data.memory.embedding.ProductionMemoryEmbeddingProvisioner
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorStore
import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorStoreFactory
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.repository.MemoryRepository
import cn.nabr.chatwithchat.data.repository.MemoryRepositoryImpl
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemoryRepositoryModule {

    @Provides
    @Singleton
    fun provideMemoryActivityLogger(memoryActivityLogDao: MemoryActivityLogDao): MemoryActivityLogger =
        RoomMemoryActivityLogger(memoryActivityLogDao)

    @Provides
    @Singleton
    fun provideMemoryPromptBuilder(): MemoryPromptBuilder = MemoryPromptBuilder()

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
    fun provideMemoryVectorStore(
        @ApplicationContext context: Context
    ): MemoryVectorStore = MemoryVectorStoreFactory(context).create()

    @Provides
    @Singleton
    fun provideMutableMemoryEmbeddingCapabilitySource(): MutableMemoryEmbeddingCapabilitySource =
        MutableMemoryEmbeddingCapabilitySource()

    @Provides
    @Singleton
    fun provideMemoryEmbeddingCapabilitySource(
        source: MutableMemoryEmbeddingCapabilitySource
    ): MemoryEmbeddingCapabilitySource = source

    @Provides
    @Singleton
    fun provideMemoryEmbeddingArtifactInstaller(
        @ApplicationContext context: Context
    ): MemoryEmbeddingArtifactInstaller = MemoryEmbeddingArtifactInstaller.fromContext(context)

    @Provides
    @Singleton
    fun provideProductionMemoryEmbeddingProvisioner(
        artifactInstaller: MemoryEmbeddingArtifactInstaller,
        capabilitySource: MutableMemoryEmbeddingCapabilitySource
    ): ProductionMemoryEmbeddingProvisioner = ProductionMemoryEmbeddingProvisioner(
        artifactInstaller = artifactInstaller,
        capabilitySource = capabilitySource
    )

    @Provides
    @Singleton
    fun provideMemoryChunker(): MemoryChunker = MemoryChunker()

    @Provides
    @Singleton
    fun provideMemoryCorpusSnapshotter(
        memoryFileStore: MemoryFileStore,
        memoryChunker: MemoryChunker
    ): MemoryCorpusSnapshotter = MemoryCorpusSnapshotter(memoryFileStore, memoryChunker)

    @Provides
    @Singleton
    fun provideMemoryIndexSyncService(
        memoryRecoveryDao: MemoryRecoveryDao,
        memoryCorpusSnapshotter: MemoryCorpusSnapshotter,
        memoryFileStore: MemoryFileStore,
        memoryVectorStore: MemoryVectorStore,
        memoryEmbeddingCapabilitySource: MemoryEmbeddingCapabilitySource
    ): MemoryIndexSyncService = MemoryIndexSynchronizer(
        recoveryDao = memoryRecoveryDao,
        snapshotSource = memoryCorpusSnapshotter,
        memoryFileStore = memoryFileStore,
        vectorStore = memoryVectorStore,
        embeddingCapabilitySource = memoryEmbeddingCapabilitySource
    )

    @Provides
    @Singleton
    fun provideMemoryVectorIndexRecoveryService(
        memoryRecoveryDao: MemoryRecoveryDao,
        memoryCorpusSnapshotter: MemoryCorpusSnapshotter,
        memoryFileStore: MemoryFileStore,
        memoryVectorStore: MemoryVectorStore,
        memoryEmbeddingCapabilitySource: MemoryEmbeddingCapabilitySource,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler
    ): MemoryVectorIndexRecoveryService = MemoryVectorIndexRecoveryService(
        recoveryDao = memoryRecoveryDao,
        snapshotSource = memoryCorpusSnapshotter,
        memoryFileStore = memoryFileStore,
        vectorStore = memoryVectorStore,
        embeddingCapabilitySource = memoryEmbeddingCapabilitySource,
        maintenanceScheduler = memoryMaintenanceScheduler
    )

    @Provides
    @Singleton
    fun provideMarkdownLexicalRetriever(
        memoryCorpusSnapshotter: MemoryCorpusSnapshotter
    ): MarkdownLexicalRetriever = MarkdownLexicalRetriever(memoryCorpusSnapshotter)

    @Provides
    @Singleton
    fun provideMemoryVectorRecallStateSource(
        memoryRecoveryDao: MemoryRecoveryDao
    ): MemoryVectorRecallStateSource = RoomMemoryVectorRecallStateSource(memoryRecoveryDao)

    @Provides
    @Singleton
    fun provideMemoryVectorRecallRepairTrigger(
        memoryMaintenanceWorkEnqueuer: MemoryMaintenanceWorkEnqueuer
    ): MemoryVectorRecallRepairTrigger =
        WorkEnqueuingMemoryVectorRecallRepairTrigger(memoryMaintenanceWorkEnqueuer)

    @Provides
    @Singleton
    fun provideHybridMemoryRetriever(
        memoryCorpusSnapshotter: MemoryCorpusSnapshotter,
        markdownLexicalRetriever: MarkdownLexicalRetriever,
        memoryVectorStore: MemoryVectorStore,
        memoryEmbeddingCapabilitySource: MemoryEmbeddingCapabilitySource,
        memoryVectorRecallStateSource: MemoryVectorRecallStateSource,
        memoryVectorRecallRepairTrigger: MemoryVectorRecallRepairTrigger
    ): HybridMemoryRetriever = HybridMemoryRetriever(
        snapshotSource = memoryCorpusSnapshotter,
        lexicalRetriever = markdownLexicalRetriever,
        vectorStore = memoryVectorStore,
        embeddingCapabilitySource = memoryEmbeddingCapabilitySource,
        vectorRecallStateSource = memoryVectorRecallStateSource,
        repairTrigger = memoryVectorRecallRepairTrigger
    )

    @Provides
    @Singleton
    fun provideMemoryRetriever(hybridMemoryRetriever: HybridMemoryRetriever): MemoryRetriever =
        hybridMemoryRetriever

    @Provides
    @Singleton
    fun provideMemoryMaintenanceCorpusReader(
        markdownLexicalRetriever: MarkdownLexicalRetriever
    ): MemoryMaintenanceCorpusReader = markdownLexicalRetriever

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
    fun provideMemoryMutationCoordinator(
        memoryRecoveryDao: MemoryRecoveryDao,
        memoryFileStore: MemoryFileStore,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        memoryMaintenanceWorkEnqueuer: MemoryMaintenanceWorkEnqueuer
    ): MemoryMutationCoordinator = MemoryMutationCoordinator(
        recoveryDao = memoryRecoveryDao,
        memoryFileStore = memoryFileStore,
        maintenanceScheduler = memoryMaintenanceScheduler,
        workEnqueuer = memoryMaintenanceWorkEnqueuer
    )

    @Provides
    @Singleton
    fun provideMemoryVectorIndexBootstrapService(
        memoryRecoveryDao: MemoryRecoveryDao,
        memoryFileStore: MemoryFileStore,
        memoryMutationCoordinator: MemoryMutationCoordinator
    ): MemoryVectorIndexBootstrapService = MemoryVectorIndexBootstrapService(
        recoveryDao = memoryRecoveryDao,
        memoryFileStore = memoryFileStore,
        mutationCoordinator = memoryMutationCoordinator
    )

    @Provides
    @Singleton
    fun provideMemoryMutationRecoveryService(
        memoryMutationCoordinator: MemoryMutationCoordinator,
        memoryTurnBatchDao: MemoryTurnBatchDao,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        memoryDailyDistillationRecoveryFinalizer: MemoryDailyDistillationRecoveryFinalizer
    ): MemoryMutationRecoveryService = MemoryMutationRecoveryService(
        memoryMutationCoordinator = memoryMutationCoordinator,
        turnBatchDao = memoryTurnBatchDao,
        maintenanceScheduler = memoryMaintenanceScheduler,
        dailyDistillationFinalizer = memoryDailyDistillationRecoveryFinalizer
    )

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
    fun provideMemoryMaintenanceLeaseWatchdog(
        memoryTurnBatchScheduler: MemoryTurnBatchScheduler
    ): MemoryMaintenanceLeaseWatchdog = memoryTurnBatchScheduler

    @Provides
    @Singleton
    fun provideMemoryDailyDistillationScheduler(
        memoryFileStore: MemoryFileStore,
        markdownMemoryCodec: MarkdownMemoryCodec,
        memoryRecoveryDao: MemoryRecoveryDao,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        settingRepository: SettingRepository,
        memoryMaintenanceWorkEnqueuer: MemoryMaintenanceWorkEnqueuer
    ): MemoryDailyDistillationScheduler = MemoryDailyDistillationScheduler(
        memoryFileStore = memoryFileStore,
        markdownMemoryCodec = markdownMemoryCodec,
        recoveryDao = memoryRecoveryDao,
        maintenanceScheduler = memoryMaintenanceScheduler,
        settingRepository = settingRepository,
        workEnqueuer = memoryMaintenanceWorkEnqueuer
    )

    @Provides
    @Singleton
    fun provideMemoryDailyDistillationOperationController(
        markdownMemoryCodec: MarkdownMemoryCodec
    ): MemoryDailyDistillationOperationController =
        MemoryDailyDistillationOperationController(markdownMemoryCodec)

    @Provides
    @Singleton
    fun provideMemoryDailyDistillationService(
        memoryRecoveryDao: MemoryRecoveryDao,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        settingRepository: SettingRepository,
        memoryIntelligence: MemoryIntelligence,
        memoryFileStore: MemoryFileStore,
        memoryDailyDistillationOperationController: MemoryDailyDistillationOperationController,
        memoryMutationCoordinator: MemoryMutationCoordinator,
        memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler
    ): MemoryDailyDistillationService = MemoryDailyDistillationService(
        recoveryDao = memoryRecoveryDao,
        maintenanceScheduler = memoryMaintenanceScheduler,
        settingRepository = settingRepository,
        memoryIntelligence = memoryIntelligence,
        memoryFileStore = memoryFileStore,
        operationController = memoryDailyDistillationOperationController,
        memoryMutationCoordinator = memoryMutationCoordinator,
        dailyDistillationScheduler = memoryDailyDistillationScheduler
    )

    @Provides
    @Singleton
    fun provideMemoryDailyDistillationRecoveryFinalizer(
        memoryDailyDistillationService: MemoryDailyDistillationService
    ): MemoryDailyDistillationRecoveryFinalizer = memoryDailyDistillationService

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
        memoryMaintenanceCorpusReader: MemoryMaintenanceCorpusReader,
        memoryMutationCoordinator: MemoryMutationCoordinator,
        memoryActivityLogger: MemoryActivityLogger
    ): MemoryBatchConsolidationService = MemoryBatchConsolidationService(
        turnBatchDao = memoryTurnBatchDao,
        maintenanceScheduler = memoryMaintenanceScheduler,
        turnBatchScheduler = memoryTurnBatchScheduler,
        settingRepository = settingRepository,
        memoryIntelligence = memoryIntelligence,
        memoryFileStore = memoryFileStore,
        markdownMemoryCodec = markdownMemoryCodec,
        memoryMaintenanceCorpusReader = memoryMaintenanceCorpusReader,
        memoryMutationCoordinator = memoryMutationCoordinator,
        activityLogger = memoryActivityLogger
    )

    @Provides
    @Singleton
    fun provideMemoryIntelligence(
        settingRepository: SettingRepository,
        openAIAPI: OpenAIAPI,
        anthropicAPI: AnthropicAPI,
        googleAPI: GoogleAPI,
        memoryActivityLogger: MemoryActivityLogger
    ): MemoryIntelligence = LlmMemoryIntelligence(settingRepository, openAIAPI, anthropicAPI, googleAPI, memoryActivityLogger)

    @Provides
    @Singleton
    fun provideMemoryRepository(
        memoryPromptBuilder: MemoryPromptBuilder,
        memoryRetriever: MemoryRetriever,
        memoryFileStore: MemoryFileStore,
        memoryTurnBatchCoordinator: MemoryTurnBatchCoordinator,
        memoryTurnBatchScheduler: MemoryTurnBatchScheduler,
        memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler
    ): MemoryRepository = MemoryRepositoryImpl(
        memoryPromptBuilder = memoryPromptBuilder,
        memoryRetriever = memoryRetriever,
        memoryFileStore = memoryFileStore,
        memoryTurnBatchCoordinator = memoryTurnBatchCoordinator,
        memoryTurnBatchScheduler = memoryTurnBatchScheduler,
        memoryDailyDistillationScheduler = memoryDailyDistillationScheduler
    )
}
