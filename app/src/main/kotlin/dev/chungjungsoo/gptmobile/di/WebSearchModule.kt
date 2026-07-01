package dev.chungjungsoo.gptmobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
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
}

