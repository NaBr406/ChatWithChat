package dev.chungjungsoo.gptmobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.tool.BuiltInTools
import dev.chungjungsoo.gptmobile.data.tool.JsonToolCallParser
import dev.chungjungsoo.gptmobile.data.tool.ToolExecutor
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopOrchestrator
import dev.chungjungsoo.gptmobile.data.tool.ToolRegistry
import dev.chungjungsoo.gptmobile.data.websearch.ProviderSearchDecisionModelClient
import dev.chungjungsoo.gptmobile.data.websearch.SearchDecisionModelClient
import dev.chungjungsoo.gptmobile.data.websearch.SearchDecisionService
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebSearchModule {

    @Provides
    @Singleton
    fun provideWebSearchRepository(networkClient: NetworkClient): WebSearchRepository =
        WebSearchRepositoryImpl(networkClient)

    @Provides
    @Singleton
    fun provideWebPageExtractor(networkClient: NetworkClient): WebPageExtractor =
        WebPageExtractor(networkClient)

    @Provides
    @Singleton
    fun provideSearchDecisionModelClient(
        openAIAPI: OpenAIAPI,
        groqAPI: GroqAPI,
        anthropicAPI: AnthropicAPI,
        googleAPI: GoogleAPI
    ): SearchDecisionModelClient = ProviderSearchDecisionModelClient(
        openAIAPI,
        groqAPI,
        anthropicAPI,
        googleAPI
    )

    @Provides
    @Singleton
    fun provideSearchDecisionService(modelClient: SearchDecisionModelClient): SearchDecisionService =
        SearchDecisionService(modelClient)

    @Provides
    @Singleton
    fun provideToolRegistry(
        webSearchRepository: WebSearchRepository,
        webPageExtractor: WebPageExtractor
    ): ToolRegistry = BuiltInTools(
        webSearchRepository,
        webPageExtractor
    ).registry()

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
