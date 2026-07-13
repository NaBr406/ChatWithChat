package dev.chungjungsoo.gptmobile.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.chungjungsoo.gptmobile.data.database.dao.ChatClassificationDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryActivityLogDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryRecoveryDao
import dev.chungjungsoo.gptmobile.data.database.dao.MemoryTurnBatchDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevisionListConverter
import dev.chungjungsoo.gptmobile.data.database.entity.ChatAttachmentListConverter
import dev.chungjungsoo.gptmobile.data.database.entity.ChatClassification
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryActivityLog
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryChatCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryCorpusState
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDistillationCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationGroup
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMutationReceipt
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryPendingTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadataListConverter
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.StringListConverter
import dev.chungjungsoo.gptmobile.data.database.entity.TokenUsageRecordConverter

@Database(
    entities = [
        ChatRoomV2::class,
        MessageV2::class,
        PlatformV2::class,
        PlatformModelV2::class,
        ChatPlatformModelV2::class,
        PersonalMemory::class,
        ChatClassification::class,
        MemoryMaintenanceJob::class,
        MemoryMutationGroup::class,
        MemoryMutationReceipt::class,
        MemoryCorpusState::class,
        MemoryDistillationCheckpoint::class,
        MemoryChatCheckpoint::class,
        MemoryPendingTurn::class,
        MemoryActivityLog::class
    ],
    version = 16,
    exportSchema = true
)
@TypeConverters(
    StringListConverter::class,
    ChatAttachmentListConverter::class,
    AssistantRevisionListConverter::class,
    MessageSourceMetadataListConverter::class,
    TokenUsageRecordConverter::class
)
abstract class ChatDatabaseV2 : RoomDatabase() {

    abstract fun platformDao(): PlatformV2Dao
    abstract fun chatRoomDao(): ChatRoomV2Dao
    abstract fun messageDao(): MessageV2Dao
    abstract fun chatPlatformModelDao(): ChatPlatformModelV2Dao
    abstract fun personalMemoryDao(): PersonalMemoryDao
    abstract fun chatClassificationDao(): ChatClassificationDao
    abstract fun platformModelDao(): PlatformModelV2Dao
    abstract fun memoryMaintenanceJobDao(): MemoryMaintenanceJobDao
    abstract fun memoryRecoveryDao(): MemoryRecoveryDao
    abstract fun memoryTurnBatchDao(): MemoryTurnBatchDao
    abstract fun memoryActivityLogDao(): MemoryActivityLogDao
}
