package cn.nabr.chatwithchat.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import cn.nabr.chatwithchat.data.datastore.SettingDataSource
import cn.nabr.chatwithchat.data.datastore.SettingDataSourceImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingDataSourceModule {
    @Provides
    @Singleton
    fun provideSettingDataStore(dataStore: DataStore<Preferences>): SettingDataSource = SettingDataSourceImpl(dataStore)
}
