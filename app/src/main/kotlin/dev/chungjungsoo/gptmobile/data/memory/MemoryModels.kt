package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.Serializable

@Serializable
data class MemoryConversationMessage(
    val role: String,
    val content: String
)

data class PreparedMemoryContext(
    val retrievedMemories: List<MemoryRetrievalResult> = emptyList(),
    val prompt: String? = null
)

object MemorySensitivity {
    const val NORMAL = "normal"
    const val PRIVATE = "private"
    const val SENSITIVE = "sensitive"
}

object MemorySource {
    const val EXPLICIT_USER_STATEMENT = "explicit_user_statement"
    const val ASSISTANT_INFERRED = "assistant_inferred"
    const val USER_CONFIRMED = "user_confirmed"
}

fun buildMemoryMessages(
    chatRoom: ChatRoomV2,
    userMessages: List<MessageV2>,
    assistantMessages: List<List<MessageV2>>,
    maxTurns: Int = 6
): List<MemoryConversationMessage> {
    val startIndex = (userMessages.size - maxTurns).coerceAtLeast(0)
    val messages = mutableListOf<MemoryConversationMessage>()
    userMessages.drop(startIndex).forEachIndexed { offset, userMessage ->
        val turnIndex = startIndex + offset
        messages.add(MemoryConversationMessage(role = "user", content = userMessage.content))
        assistantMessages.getOrNull(turnIndex)
            ?.firstOrNull { it.content.isNotBlank() }
            ?.let { assistantMessage ->
                messages.add(MemoryConversationMessage(role = "assistant", content = assistantMessage.content))
            }
    }

    if (messages.isEmpty() && chatRoom.title.isNotBlank()) {
        return listOf(MemoryConversationMessage(role = "system_context", content = "Chat title: ${chatRoom.title}"))
    }

    return messages
}
