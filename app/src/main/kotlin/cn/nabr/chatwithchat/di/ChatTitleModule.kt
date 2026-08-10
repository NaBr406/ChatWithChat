package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.context.ContextCompactionModelClient
import cn.nabr.chatwithchat.data.title.ChatTitleGenerationService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binding for LLM chat-title generation.
 *
 * It deliberately takes the same [ContextCompactionModelClient] binding as context compaction rather
 * than a client of its own, so both auxiliary paths share the one mutex that serialises writes to
 * the mutable-state API singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatTitleModule {

    @Provides
    @Singleton
    fun provideChatTitleGenerationService(
        modelClient: ContextCompactionModelClient
    ): ChatTitleGenerationService = ChatTitleGenerationService(modelClient)
}
