package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.generation.AndroidChatGenerationKeepAlive
import cn.nabr.chatwithchat.data.generation.ChatGenerationKeepAlive
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatGenerationModule {
    @Binds
    abstract fun bindChatGenerationKeepAlive(implementation: AndroidChatGenerationKeepAlive): ChatGenerationKeepAlive
}
