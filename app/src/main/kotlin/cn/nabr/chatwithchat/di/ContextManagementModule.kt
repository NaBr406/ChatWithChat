package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.context.ContextCheckpointRepository
import cn.nabr.chatwithchat.data.context.ContextCheckpointRepositoryImpl
import cn.nabr.chatwithchat.data.context.ContextCompactionModelClient
import cn.nabr.chatwithchat.data.context.ContextCompactionService
import cn.nabr.chatwithchat.data.context.OpenAINativeCompactionService
import cn.nabr.chatwithchat.data.context.ProviderContextCompactionModelClient
import cn.nabr.chatwithchat.data.database.dao.ContextManagementDao
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.GroqAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * The lock serialising every context-compaction call against every other one. Qualified so it cannot
 * be mistaken for a general-purpose `Mutex` binding by anything else in the graph.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ContextCompactionClientLock

/**
 * Bindings for context management: the checkpoint chain, the per-chat range, the OpenAI native
 * compaction state, and the two compaction services.
 *
 * The implementations are constructed here rather than annotated with `@Inject` because several take
 * defaulted constructor parameters — a clock, a shared mutex — that Dagger cannot satisfy on its
 * own, and leaving them defaulted keeps them swappable from tests without a production-only seam.
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
    @ContextCompactionClientLock
    fun provideContextCompactionClientLock(): Mutex = Mutex()

    /**
     * Shares one lock with [provideOpenAINativeCompactionService]: both mutate the same
     * `@Singleton` API clients, so separate locks would not actually serialise anything.
     */
    @Provides
    @Singleton
    fun provideContextCompactionModelClient(
        openAIAPI: OpenAIAPI,
        groqAPI: GroqAPI,
        anthropicAPI: AnthropicAPI,
        googleAPI: GoogleAPI,
        @ContextCompactionClientLock clientLock: Mutex
    ): ContextCompactionModelClient = ProviderContextCompactionModelClient(
        openAIAPI = openAIAPI,
        groqAPI = groqAPI,
        anthropicAPI = anthropicAPI,
        googleAPI = googleAPI,
        clientMutex = clientLock
    )

    @Provides
    @Singleton
    fun provideContextCompactionService(
        modelClient: ContextCompactionModelClient
    ): ContextCompactionService = ContextCompactionService(modelClient)

    @Provides
    @Singleton
    fun provideOpenAINativeCompactionService(
        openAIAPI: OpenAIAPI,
        @ContextCompactionClientLock clientLock: Mutex
    ): OpenAINativeCompactionService = OpenAINativeCompactionService(
        openAIAPI = openAIAPI,
        clientMutex = clientLock
    )
}
