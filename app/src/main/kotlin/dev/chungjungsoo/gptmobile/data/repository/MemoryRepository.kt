package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
import dev.chungjungsoo.gptmobile.data.memory.MemoryLearningResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryTurnRecordingResult
import dev.chungjungsoo.gptmobile.data.memory.PreparedMemoryContext

interface MemoryRepository {
    suspend fun onMemoryEnabledChanged(enabled: Boolean)

    suspend fun recordUserActivity(chatId: Int, activityAt: Long)

    suspend fun recordCompletedTurn(input: MemoryCompletedTurnInput): MemoryTurnRecordingResult

    suspend fun prepareMemoryContext(
        chatRoom: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        memoryPlatform: PlatformV2? = null
    ): PreparedMemoryContext

    suspend fun learnFromChat(
        chatRoom: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        memoryPlatform: PlatformV2? = null,
        learningIdempotencyKey: String? = null
    ): MemoryLearningResult

    suspend fun getMemories(): List<PersonalMemory>
    suspend fun updateMemory(memory: PersonalMemory)
    suspend fun deleteMemory(memory: PersonalMemory)
    suspend fun confirmMemory(memory: PersonalMemory)
    suspend fun rejectMemory(memory: PersonalMemory)
    suspend fun markResolved(memory: PersonalMemory)
    suspend fun archiveMemory(memory: PersonalMemory)
    suspend fun exportMarkdown(): String
    suspend fun getLongTermMarkdown(): String
    suspend fun migrateActiveMemoriesToMarkdown(): Int
    suspend fun getMaintenanceJobs(): List<MemoryMaintenanceJob>
    suspend fun retryMaintenanceJob(jobId: String)
    suspend fun dismissMaintenanceJob(jobId: String)
}
