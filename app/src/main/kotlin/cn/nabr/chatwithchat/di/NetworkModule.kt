package cn.nabr.chatwithchat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.AnthropicAPIImpl
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.GoogleAPIImpl
import cn.nabr.chatwithchat.data.network.GroqAPI
import cn.nabr.chatwithchat.data.network.GroqAPIImpl
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPIImpl
import io.ktor.client.engine.cio.CIO
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideNetworkClient(): NetworkClient = NetworkClient(CIO)

    @Provides
    @Singleton
    fun provideOpenAIAPI(networkClient: NetworkClient): OpenAIAPI = OpenAIAPIImpl(networkClient)

    @Provides
    @Singleton
    fun provideGroqAPI(networkClient: NetworkClient): GroqAPI = GroqAPIImpl(networkClient)

    @Provides
    @Singleton
    fun provideAnthropicAPI(networkClient: NetworkClient): AnthropicAPI = AnthropicAPIImpl(networkClient)

    @Provides
    @Singleton
    fun provideGoogleAPI(networkClient: NetworkClient): GoogleAPI = GoogleAPIImpl(networkClient)
}
