package cn.nabr.chatwithchat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import cn.nabr.chatwithchat.data.database.dao.ChatPlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformV2Dao
import cn.nabr.chatwithchat.data.datastore.SettingDataSource
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.repository.ModelDiscoveryRepository
import cn.nabr.chatwithchat.data.repository.ModelDiscoveryRepositoryImpl
import cn.nabr.chatwithchat.data.repository.SettingRepository
import cn.nabr.chatwithchat.data.repository.SettingRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingRepositoryModule {

    @Provides
    @Singleton
    fun provideSettingRepository(
        settingDataSource: SettingDataSource,
        platformV2Dao: PlatformV2Dao,
        platformModelV2Dao: PlatformModelV2Dao,
        chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
        modelDiscoveryRepository: ModelDiscoveryRepository
    ): SettingRepository = SettingRepositoryImpl(
        settingDataSource,
        platformV2Dao,
        platformModelV2Dao,
        chatPlatformModelV2Dao,
        modelDiscoveryRepository
    )

    @Provides
    @Singleton
    fun provideModelDiscoveryRepository(
        networkClient: NetworkClient
    ): ModelDiscoveryRepository = ModelDiscoveryRepositoryImpl(networkClient)
}
