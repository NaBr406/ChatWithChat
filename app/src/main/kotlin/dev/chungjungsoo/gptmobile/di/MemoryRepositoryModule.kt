package dev.chungjungsoo.gptmobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryActivityLogDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryIndexDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.memory.HybridMemoryRetriever
import dev.chungjungsoo.gptmobile.data.memory.LlmMemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.MarkdownLexicalRetriever
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryCodec
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryDebugEditor
import dev.chungjungsoo.gptmobile.data.memory.MemoryActivityLogger
import dev.chungjungsoo.gptmobile.data.memory.MemoryBatchConsolidationService
import dev.chungjungsoo.gptmobile.data.memory.MemoryChunker
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpusSnapshotter
import dev.chungjungsoo.gptmobile.data.memory.MemoryDailyDistillationOperationController
import dev.chungjungsoo.gptmobile.data.memory.MemoryDailyDistillationRecoveryFinalizer
import dev.chungjungsoo.gptmobile.data.memory.MemoryDailyDistillationScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryDailyDistillationService
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRepository
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexSyncService
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexSynchronizer
import dev.chungjungsoo.gptmobile.data.memory.MemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceCorpusReader
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceEventSink
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceLeaseWatchdog
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceNotificationEventSink
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceNotificationPolicy
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkEnqueuer
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMarkdownCodec
import dev.chungjungsoo.gptmobile.data.memory.MemoryMutationCoordinator
import dev.chungjungsoo.gptmobile.data.memory.MemoryMutationRecoveryService
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryRetriever
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchCoordinator
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnBatchScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryVectorIndexBootstrapService
import dev.chungjungsoo.gptmobile.data.memory.MemoryVectorIndexRecoveryService
import dev.chungjungsoo.gptmobile.data.memory.MemoryVectorRecallRepairTrigger
import dev.chungjungsoo.gptmobile.data.memory.MemoryVectorRecallStateSource
import dev.chungjungsoo.gptmobile.data.memory.RoomMemoryActivityLogger
import dev.chungjungsoo.gptmobile.data.memory.RoomMemoryVectorRecallStateSource
import dev.chungjungsoo.gptmobile.data.memory.WorkEnqueuingMemoryVectorRecallRepairTrigger
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingArtifactInstaller
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.embedding.MutableMemoryEmbeddingCapabilitySource
import dev.chungjungsoo.gptmobile.data.memory.embedding.ProductionMemoryEmbeddingProvisioner
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStore
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStoreFactory
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
    fun provideMemoryActivityLogger(memoryActivityLogDao: MemoryActivityLogDao): MemoryActivityLogger =
        RoomMemoryActivityLogger(memoryActivityLogDao)

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
        personalMemoryDao: PersonalMemoryDao,
        memoryPromptBuilder: MemoryPromptBuilder,
        memoryRetriever: MemoryRetriever,
        memoryIndexRepository: MemoryIndexRepository,
        memoryFileStore: MemoryFileStore,
        markdownMemoryCodec: MarkdownMemoryCodec,
        memoryTurnBatchCoordinator: MemoryTurnBatchCoordinator,
        memoryTurnBatchScheduler: MemoryTurnBatchScheduler,
        memoryDailyDistillationScheduler: MemoryDailyDistillationScheduler
    ): MemoryRepository = MemoryRepositoryImpl(
        personalMemoryDao = personalMemoryDao,
        memoryPromptBuilder = memoryPromptBuilder,
        memoryRetriever = memoryRetriever,
        memoryFileStore = memoryFileStore,
        markdownMemoryCodec = markdownMemoryCodec,
        memoryIndexRebuilder = memoryIndexRepository,
        memoryTurnBatchCoordinator = memoryTurnBatchCoordinator,
        memoryTurnBatchScheduler = memoryTurnBatchScheduler,
        memoryDailyDistillationScheduler = memoryDailyDistillationScheduler
    )
}
