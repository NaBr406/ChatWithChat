package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.context.ContextCheckpointRepository
import cn.nabr.chatwithchat.data.context.ContextCheckpointRepositoryImpl
import cn.nabr.chatwithchat.data.database.dao.ContextManagementDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for context management: the checkpoint chain, the per-chat range, and the OpenAI native
 * compaction state.
 *
 * The implementation is constructed here rather than annotated with `@Inject` because its clock is a
 * defaulted lambda, which Dagger cannot satisfy — and leaving it defaulted keeps it swappable from
 * tests without a production-only seam.
 */
@Module
@InstallIn(SingletonComponent::class)
object ContextManagementModule {

    @Provides
    @Singleton
    fun provideContextCheckpointRepository(
        contextManagementDao: ContextManagementDao
    ): ContextCheckpointRepository = ContextCheckpointRepositoryImpl(contextManagementDao)
}
