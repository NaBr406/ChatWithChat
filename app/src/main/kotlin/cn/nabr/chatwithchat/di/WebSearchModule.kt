package cn.nabr.chatwithchat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.GroqAPI
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.repository.SettingRepository
import cn.nabr.chatwithchat.data.websearch.ProviderSearchDecisionModelClient
import cn.nabr.chatwithchat.data.websearch.SearchDecisionModelClient
import cn.nabr.chatwithchat.data.websearch.SearchDecisionService
import cn.nabr.chatwithchat.data.websearch.WebPageExtractor
import cn.nabr.chatwithchat.data.websearch.WebSearchRepository
import cn.nabr.chatwithchat.data.websearch.WebSearchRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebSearchModule {

    @Provides
    @Singleton
    fun provideWebSearchRepository(
        networkClient: NetworkClient,
        settingRepository: SettingRepository
    ): WebSearchRepository = WebSearchRepositoryImpl(networkClient, settingRepository)

    @Provides
    @Singleton
    fun provideWebPageExtractor(): WebPageExtractor = WebPageExtractor()

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
}
