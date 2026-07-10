package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
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

    suspend fun getLongTermMarkdown(): String
    suspend fun migrateActiveMemoriesToMarkdown(): Int
}
