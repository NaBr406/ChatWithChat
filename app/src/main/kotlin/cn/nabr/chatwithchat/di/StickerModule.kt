package cn.nabr.chatwithchat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import cn.nabr.chatwithchat.data.sticker.StickerRepository
import cn.nabr.chatwithchat.data.sticker.StickerRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StickerModule {
    @Provides
    @Singleton
    fun provideStickerRepository(repository: StickerRepositoryImpl): StickerRepository = repository
}
