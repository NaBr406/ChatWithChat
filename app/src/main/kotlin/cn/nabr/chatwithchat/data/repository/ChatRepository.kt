package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.database.entity.ChatRoom
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.Message
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.model.ChatPlatformConfig
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.defaultReasoningMode
import cn.nabr.chatwithchat.data.database.entity.MessageTextSkillInvocation
import cn.nabr.chatwithchat.data.database.entity.SessionTextSkillBinding
import cn.nabr.chatwithchat.data.textskill.ChatRequestContext
import cn.nabr.chatwithchat.data.textskill.TextSkillInvocationSnapshot
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        requestContext: ChatRequestContext = ChatRequestContext(),
        reasoningMode: ReasoningMode = platform.defaultReasoningMode()
    ): Flow<ApiState>

    suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        memoryPrompt: String?,
        reasoningMode: ReasoningMode = platform.defaultReasoningMode()
    ): Flow<ApiState> = completeChat(
        userMessages = userMessages,
        assistantMessages = assistantMessages,
        platform = platform,
        requestContext = ChatRequestContext(memoryPrompt = memoryPrompt),
        reasoningMode = reasoningMode
    )

    suspend fun fetchChatList(): List<ChatRoom>
    suspend fun fetchChatListV2(): List<ChatRoomV2>
    suspend fun searchChatsV2(query: String): List<ChatRoomV2>
    suspend fun fetchMessages(chatId: Int): List<Message>
    suspend fun fetchMessagesV2(chatId: Int): List<MessageV2>
    suspend fun findChatIdByInitialRequestId(initialRequestId: Int): Int? = null
    suspend fun fetchChatPlatformModels(chatId: Int): Map<String, ChatPlatformConfig>
    suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, ChatPlatformConfig>)
    suspend fun migrateToChatRoomV2MessageV2()
    fun generateDefaultChatTitle(messages: List<MessageV2>): String?
    suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String)
    suspend fun saveChat(chatRoom: ChatRoomV2, messages: List<MessageV2>, chatPlatformModels: Map<String, ChatPlatformConfig>): ChatRoomV2
    suspend fun saveChatWithTextSkillInvocation(
        chatRoom: ChatRoomV2,
        messages: List<MessageV2>,
        chatPlatformModels: Map<String, ChatPlatformConfig>,
        snapshot: TextSkillInvocationSnapshot
    ): ChatRoomV2 = saveChat(chatRoom, messages, chatPlatformModels)
    suspend fun duplicateChatV2(chatRoom: ChatRoomV2): ChatRoomV2
    suspend fun deleteChats(chatRooms: List<ChatRoom>)
    suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>)
    suspend fun fetchSessionTextSkillBindings(chatId: Int): List<SessionTextSkillBinding> = emptyList()
    suspend fun fetchSessionTextSkillBindingsForPackage(packageId: String): List<SessionTextSkillBinding> = emptyList()
    suspend fun saveSessionTextSkillBinding(binding: SessionTextSkillBinding) {}
    suspend fun setSessionTextSkillBindingEnabled(
        chatId: Int,
        packageId: String,
        skillId: String,
        variantKey: String,
        enabled: Boolean
    ) {}
    suspend fun removeSessionTextSkillBinding(chatId: Int, packageId: String, skillId: String, variantKey: String) {}
    suspend fun disableTextSkillBindingsForPackage(packageId: String) {}
    suspend fun textSkillBindingCount(packageId: String): Int = 0
    suspend fun saveTextSkillInvocation(userMessageId: Int, chatId: Int, snapshot: TextSkillInvocationSnapshot) {}
    suspend fun fetchTextSkillInvocation(userMessageId: Int): TextSkillInvocationSnapshot? = null
}
