package dev.chungjungsoo.gptmobile.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.database.ChatDatabase
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2Migrations
import dev.chungjungsoo.gptmobile.data.database.dao.ChatClassificationDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryIndexDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DB_NAME = "chat"
    private const val DB_NAME_V2 = "chat_v2"

    @Provides
    fun provideChatPlatformModelV2Dao(chatDatabaseV2: ChatDatabaseV2): ChatPlatformModelV2Dao = chatDatabaseV2.chatPlatformModelDao()

    @Provides
    fun providePlatformV2Dao(chatDatabaseV2: ChatDatabaseV2): PlatformV2Dao = chatDatabaseV2.platformDao()

    @Provides
    fun providePlatformModelV2Dao(chatDatabaseV2: ChatDatabaseV2): PlatformModelV2Dao = chatDatabaseV2.platformModelDao()

    @Provides
    fun provideChatRoomDao(chatDatabase: ChatDatabase): ChatRoomDao = chatDatabase.chatRoomDao()

    @Provides
    fun provideChatRoomV2Dao(chatDatabaseV2: ChatDatabaseV2): ChatRoomV2Dao = chatDatabaseV2.chatRoomDao()

    @Provides
    fun provideMessageDao(chatDatabase: ChatDatabase): MessageDao = chatDatabase.messageDao()

    @Provides
    fun provideMessageV2Dao(chatDatabaseV2: ChatDatabaseV2): MessageV2Dao = chatDatabaseV2.messageDao()

    @Provides
    fun providePersonalMemoryDao(chatDatabaseV2: ChatDatabaseV2): PersonalMemoryDao = chatDatabaseV2.personalMemoryDao()

    @Provides
    fun provideChatClassificationDao(chatDatabaseV2: ChatDatabaseV2): ChatClassificationDao = chatDatabaseV2.chatClassificationDao()

    @Provides
    fun provideMemoryIndexDao(chatDatabaseV2: ChatDatabaseV2): MemoryIndexDao = chatDatabaseV2.memoryIndexDao()

    @Provides
    fun provideMemoryMaintenanceJobDao(chatDatabaseV2: ChatDatabaseV2): MemoryMaintenanceJobDao = chatDatabaseV2.memoryMaintenanceJobDao()

    @Provides
    fun provideMemoryTurnBatchDao(chatDatabaseV2: ChatDatabaseV2): MemoryTurnBatchDao = chatDatabaseV2.memoryTurnBatchDao()

    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext appContext: Context): ChatDatabase = Room.databaseBuilder(
        appContext,
        ChatDatabase::class.java,
        DB_NAME
    ).build()

    @Provides
    @Singleton
    fun provideChatDatabaseV2(@ApplicationContext appContext: Context): ChatDatabaseV2 = Room.databaseBuilder(
        appContext,
        ChatDatabaseV2::class.java,
        DB_NAME_V2
    ).addMigrations(
        ChatDatabaseV2Migrations.MIGRATION_1_2,
        ChatDatabaseV2Migrations.MIGRATION_2_3,
        ChatDatabaseV2Migrations.MIGRATION_3_4,
        ChatDatabaseV2Migrations.MIGRATION_4_5,
        ChatDatabaseV2Migrations.MIGRATION_5_6,
        ChatDatabaseV2Migrations.MIGRATION_6_7,
        ChatDatabaseV2Migrations.MIGRATION_7_8,
        ChatDatabaseV2Migrations.MIGRATION_8_9,
        ChatDatabaseV2Migrations.MIGRATION_9_10,
        ChatDatabaseV2Migrations.MIGRATION_10_11,
        ChatDatabaseV2Migrations.MIGRATION_11_12,
        ChatDatabaseV2Migrations.MIGRATION_12_13
    ).build()
}
