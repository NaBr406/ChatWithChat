package cn.nabr.chatwithchat.di

import cn.nabr.chatwithchat.data.database.dao.ChatPlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformV2Dao
import cn.nabr.chatwithchat.data.datastore.SettingDataSource
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkEnqueuer
import cn.nabr.chatwithchat.data.memory.MemoryModelDependencyNotifier
import cn.nabr.chatwithchat.data.memory.SchedulerMemoryModelDependencyNotifier
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.repository.ModelDiscoveryRepository
import cn.nabr.chatwithchat.data.repository.ModelDiscoveryRepositoryImpl
import cn.nabr.chatwithchat.data.repository.SettingRepository
import cn.nabr.chatwithchat.data.repository.SettingRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
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
        modelDiscoveryRepository: ModelDiscoveryRepository,
        memoryModelDependencyNotifier: MemoryModelDependencyNotifier
    ): SettingRepository = SettingRepositoryImpl(
        settingDataSource,
        platformV2Dao,
        platformModelV2Dao,
        chatPlatformModelV2Dao,
        modelDiscoveryRepository,
        memoryModelDependencyNotifier
    )

    @Provides
    @Singleton
    fun provideMemoryModelDependencyNotifier(
        memoryMaintenanceScheduler: Provider<MemoryMaintenanceScheduler>,
        memoryMaintenanceWorkEnqueuer: MemoryMaintenanceWorkEnqueuer
    ): MemoryModelDependencyNotifier = SchedulerMemoryModelDependencyNotifier(
        schedulerProvider = memoryMaintenanceScheduler,
        workEnqueuer = memoryMaintenanceWorkEnqueuer
    )

    @Provides
    @Singleton
    fun provideModelDiscoveryRepository(
        networkClient: NetworkClient
    ): ModelDiscoveryRepository = ModelDiscoveryRepositoryImpl(networkClient)
}
