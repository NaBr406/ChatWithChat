package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatClassification
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory

interface MemoryRepository {
    suspend fun classifyChat(
        chatId: Int,
        latestUserMessage: MessageV2,
        allUserMessages: List<MessageV2>
    ): ChatClassification

    suspend fun retrieveMemories(
        classification: ChatClassification,
        latestUserMessage: MessageV2,
        limit: Int = 8
    ): List<PersonalMemory>

    fun buildMemoryPrompt(
        memories: List<PersonalMemory>,
        classification: ChatClassification
    ): String?

    suspend fun learnFromChat(
        chatRoom: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>
    )

    suspend fun getAllMemories(): List<PersonalMemory>
    suspend fun updateMemoryContent(memoryId: Int, content: String)
    suspend fun markMemoryResolved(memoryId: Int)
    suspend fun archiveMemory(memoryId: Int)
    suspend fun deleteMemory(memoryId: Int)
}
