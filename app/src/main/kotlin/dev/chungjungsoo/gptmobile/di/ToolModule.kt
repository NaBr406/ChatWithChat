package dev.chungjungsoo.gptmobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.tool.BuiltInTools
import dev.chungjungsoo.gptmobile.data.tool.JsonToolCallParser
import dev.chungjungsoo.gptmobile.data.tool.ToolExecutor
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopOrchestrator
import dev.chungjungsoo.gptmobile.data.tool.ToolProvider
import dev.chungjungsoo.gptmobile.data.tool.ToolRegistry
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolModule {
    @Provides
    @Singleton
    fun provideToolProviders(
        webSearchRepository: WebSearchRepository,
        webPageExtractor: WebPageExtractor
    ): List<ToolProvider> = BuiltInTools(
        webSearchRepository,
        webPageExtractor
    ).providers()

    @Provides
    @Singleton
    fun provideToolRegistry(toolProviders: List<ToolProvider>): ToolRegistry = ToolRegistry(toolProviders)

    @Provides
    @Singleton
    fun provideToolExecutor(toolRegistry: ToolRegistry): ToolExecutor = ToolExecutor(toolRegistry)

    @Provides
    @Singleton
    fun provideJsonToolCallParser(): JsonToolCallParser = JsonToolCallParser()

    @Provides
    @Singleton
    fun provideToolLoopOrchestrator(
        toolExecutor: ToolExecutor,
        jsonToolCallParser: JsonToolCallParser
    ): ToolLoopOrchestrator = ToolLoopOrchestrator(
        toolExecutor = toolExecutor,
        jsonToolCallParser = jsonToolCallParser
    )
}
