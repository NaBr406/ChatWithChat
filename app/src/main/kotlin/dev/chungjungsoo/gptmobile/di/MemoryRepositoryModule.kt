package dev.chungjungsoo.gptmobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.database.dao.ChatClassificationDao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemoryRepositoryModule {

    @Provides
    @Singleton
    fun provideMemoryRepository(
        personalMemoryDao: PersonalMemoryDao,
        chatClassificationDao: ChatClassificationDao
    ): MemoryRepository = MemoryRepositoryImpl(personalMemoryDao, chatClassificationDao)
}
