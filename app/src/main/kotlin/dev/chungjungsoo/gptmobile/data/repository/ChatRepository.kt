package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoom
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.Message
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.model.ChatPlatformConfig
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
import dev.chungjungsoo.gptmobile.data.model.defaultReasoningMode
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String? = null,
        reasoningMode: ReasoningMode = platform.defaultReasoningMode()
    ): Flow<ApiState>

    suspend fun fetchChatList(): List<ChatRoom>
    suspend fun fetchChatListV2(): List<ChatRoomV2>
    suspend fun searchChatsV2(query: String): List<ChatRoomV2>
    suspend fun fetchMessages(chatId: Int): List<Message>
    suspend fun fetchMessagesV2(chatId: Int): List<MessageV2>
    suspend fun fetchChatPlatformModels(chatId: Int): Map<String, ChatPlatformConfig>
    suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, ChatPlatformConfig>)
    suspend fun migrateToChatRoomV2MessageV2()
    fun generateDefaultChatTitle(messages: List<MessageV2>): String?
    suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String)
    suspend fun saveChat(chatRoom: ChatRoomV2, messages: List<MessageV2>, chatPlatformModels: Map<String, ChatPlatformConfig>): ChatRoomV2
    suspend fun duplicateChatV2(chatRoom: ChatRoomV2): ChatRoomV2
    suspend fun deleteChats(chatRooms: List<ChatRoom>)
    suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>)
}
