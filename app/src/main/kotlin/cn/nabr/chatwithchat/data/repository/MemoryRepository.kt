package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.memory.MemoryCompletedTurnInput
import cn.nabr.chatwithchat.data.memory.MemoryTurnRecordingResult
import cn.nabr.chatwithchat.data.memory.PreparedMemoryContext
import kotlinx.coroutines.flow.Flow

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
    fun observeLongTermMarkdown(): Flow<String>
}
