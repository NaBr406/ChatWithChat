package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.context.ContextBuilder
import cn.nabr.chatwithchat.data.context.ContextCheckpointRepository
import cn.nabr.chatwithchat.data.context.ContextCheckpointRepositoryImpl
import cn.nabr.chatwithchat.data.context.ContextCompactionModelClient
import cn.nabr.chatwithchat.data.context.ContextCompactionService
import cn.nabr.chatwithchat.data.context.ContextFoldDegradationRegistry
import cn.nabr.chatwithchat.data.context.ContextFoldScheduler
import cn.nabr.chatwithchat.data.context.ContextFoldService
import cn.nabr.chatwithchat.data.context.ContextWindowResolver
import cn.nabr.chatwithchat.data.context.OpenAINativeCompactionService
import cn.nabr.chatwithchat.data.context.ProviderContextCompactionModelClient
import cn.nabr.chatwithchat.data.database.dao.ContextManagementDao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformV2Dao
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkEnqueuer
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.GroqAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for context management: the checkpoint chain, the per-chat range, the OpenAI native
 * compaction state, and the two compaction services.
 *
 * The implementations are constructed here rather than annotated with `@Inject` because several take
 * defaulted constructor parameters — a clock, for instance — that Dagger cannot satisfy on its own,
 * and leaving them defaulted keeps them swappable from tests without a production-only seam.
 */
@Module
@InstallIn(SingletonComponent::class)
object ContextManagementModule {

    @Provides
    @Singleton
    fun provideContextCheckpointRepository(
        contextManagementDao: ContextManagementDao
    ): ContextCheckpointRepository = ContextCheckpointRepositoryImpl(contextManagementDao)

    @Provides
    @Singleton
    fun provideContextCompactionModelClient(
        openAIAPI: OpenAIAPI,
        groqAPI: GroqAPI,
        anthropicAPI: AnthropicAPI,
        googleAPI: GoogleAPI
    ): ContextCompactionModelClient = ProviderContextCompactionModelClient(
        openAIAPI = openAIAPI,
        groqAPI = groqAPI,
        anthropicAPI = anthropicAPI,
        googleAPI = googleAPI
    )

    @Provides
    @Singleton
    fun provideContextCompactionService(
        modelClient: ContextCompactionModelClient
    ): ContextCompactionService = ContextCompactionService(modelClient)

    @Provides
    @Singleton
    fun provideOpenAINativeCompactionService(
        openAIAPI: OpenAIAPI
    ): OpenAINativeCompactionService = OpenAINativeCompactionService(openAIAPI = openAIAPI)

    @Provides
    @Singleton
    fun provideContextFoldDegradationRegistry(): ContextFoldDegradationRegistry =
        ContextFoldDegradationRegistry()

    @Provides
    @Singleton
    fun provideContextFoldScheduler(
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        contextCheckpointRepository: ContextCheckpointRepository,
        memoryMaintenanceWorkEnqueuer: MemoryMaintenanceWorkEnqueuer,
        contextFoldDegradationRegistry: ContextFoldDegradationRegistry
    ): ContextFoldScheduler = ContextFoldScheduler(
        maintenanceScheduler = memoryMaintenanceScheduler,
        checkpointRepository = contextCheckpointRepository,
        workEnqueuer = memoryMaintenanceWorkEnqueuer,
        degradationRegistry = contextFoldDegradationRegistry
    )

    /**
     * Bound non-null even though `MemoryMaintenanceProcessor` requests it as nullable: the nullable
     * parameter exists so tests can construct a processor without the context stack, not to make the
     * dependency optional in the app. A missing binding here would silently defer every fold.
     */
    @Provides
    @Singleton
    fun provideContextFoldService(
        messageV2Dao: MessageV2Dao,
        platformV2Dao: PlatformV2Dao,
        contextBuilder: ContextBuilder,
        contextCheckpointRepository: ContextCheckpointRepository,
        contextCompactionService: ContextCompactionService,
        contextWindowResolver: ContextWindowResolver,
        memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
        memoryTurnBatchScheduler: MemoryTurnBatchScheduler,
        contextFoldDegradationRegistry: ContextFoldDegradationRegistry
    ): ContextFoldService = ContextFoldService(
        messageDao = messageV2Dao,
        platformDao = platformV2Dao,
        contextBuilder = contextBuilder,
        checkpointRepository = contextCheckpointRepository,
        compactionService = contextCompactionService,
        contextWindowResolver = contextWindowResolver,
        maintenanceScheduler = memoryMaintenanceScheduler,
        memoryTurnBatchScheduler = memoryTurnBatchScheduler,
        degradationRegistry = contextFoldDegradationRegistry
    )
}
