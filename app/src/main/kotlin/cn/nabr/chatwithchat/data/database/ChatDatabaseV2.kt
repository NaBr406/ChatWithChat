package cn.nabr.chatwithchat.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cn.nabr.chatwithchat.data.database.dao.ChatPlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.ChatRoomV2Dao
import cn.nabr.chatwithchat.data.database.dao.ChatHistoryDao
import cn.nabr.chatwithchat.data.database.dao.MemoryActivityLogDao
import cn.nabr.chatwithchat.data.database.dao.MemoryLongTermConsolidationDao
import cn.nabr.chatwithchat.data.database.dao.MemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.database.dao.MemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.dao.MemoryTurnBatchDao
import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformV2Dao
import cn.nabr.chatwithchat.data.database.dao.StickerCatalogDao
import cn.nabr.chatwithchat.data.database.entity.AssistantRevisionListConverter
import cn.nabr.chatwithchat.data.database.entity.ChatAttachmentListConverter
import cn.nabr.chatwithchat.data.database.entity.ChatPlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryBackfillCheckpointEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryEmbeddingEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexQueueEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryIndexStateEntity
import cn.nabr.chatwithchat.data.database.entity.ChatHistoryProjectionEntity
import cn.nabr.chatwithchat.data.database.entity.MemoryActivityLog
import cn.nabr.chatwithchat.data.database.entity.MemoryChatCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryCorpusState
import cn.nabr.chatwithchat.data.database.entity.MemoryDistillationCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryLongTermConsolidationCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.MemoryMutationGroup
import cn.nabr.chatwithchat.data.database.entity.MemoryMutationReceipt
import cn.nabr.chatwithchat.data.database.entity.MemoryPendingTurn
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadataListConverter
import cn.nabr.chatwithchat.data.database.entity.MessageStickerRefListConverter
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.StickerAssetEntity
import cn.nabr.chatwithchat.data.database.entity.StickerItemEntity
import cn.nabr.chatwithchat.data.database.entity.StickerPackEntity
import cn.nabr.chatwithchat.data.database.entity.StringListConverter
import cn.nabr.chatwithchat.data.database.entity.TokenUsageRecordConverter

@Database(
    entities = [
        ChatRoomV2::class,
        MessageV2::class,
        PlatformV2::class,
        PlatformModelV2::class,
        ChatPlatformModelV2::class,
        MemoryMaintenanceJob::class,
        MemoryMutationGroup::class,
        MemoryMutationReceipt::class,
        MemoryCorpusState::class,
        MemoryDistillationCheckpoint::class,
        MemoryLongTermConsolidationCheckpoint::class,
        MemoryChatCheckpoint::class,
        MemoryPendingTurn::class,
        MemoryActivityLog::class,
        StickerPackEntity::class,
        StickerAssetEntity::class,
        StickerItemEntity::class,
        ChatHistoryProjectionEntity::class,
        ChatHistoryIndexQueueEntity::class,
        ChatHistoryBackfillCheckpointEntity::class,
        ChatHistoryIndexStateEntity::class,
        ChatHistoryEmbeddingEntity::class
    ],
    version = 20,
    exportSchema = true
)
@TypeConverters(
    StringListConverter::class,
    ChatAttachmentListConverter::class,
    MessageStickerRefListConverter::class,
    AssistantRevisionListConverter::class,
    MessageSourceMetadataListConverter::class,
    TokenUsageRecordConverter::class
)
abstract class ChatDatabaseV2 : RoomDatabase() {

    abstract fun platformDao(): PlatformV2Dao
    abstract fun chatRoomDao(): ChatRoomV2Dao
    abstract fun messageDao(): MessageV2Dao
    abstract fun chatPlatformModelDao(): ChatPlatformModelV2Dao
    abstract fun platformModelDao(): PlatformModelV2Dao
    abstract fun memoryMaintenanceJobDao(): MemoryMaintenanceJobDao
    abstract fun memoryRecoveryDao(): MemoryRecoveryDao
    abstract fun memoryTurnBatchDao(): MemoryTurnBatchDao
    abstract fun memoryActivityLogDao(): MemoryActivityLogDao
    abstract fun memoryLongTermConsolidationDao(): MemoryLongTermConsolidationDao
    abstract fun stickerCatalogDao(): StickerCatalogDao
    abstract fun chatHistoryDao(): ChatHistoryDao
}
