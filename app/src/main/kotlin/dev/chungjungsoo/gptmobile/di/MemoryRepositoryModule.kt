package dev.chungjungsoo.gptmobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.database.dao.ChatClassificationDao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.memory.LlmMemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.MemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.MemoryMarkdownCodec
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
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
        memoryMarkdownCodec: MemoryMarkdownCodec
    ): MemoryRepository = MemoryRepositoryImpl(
        personalMemoryDao,
        chatClassificationDao,
        memoryIntelligence,
        memoryPromptBuilder,
        memoryMarkdownCodec
    )
}
