package dev.chungjungsoo.gptmobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MemorySeedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var memoryRepository: MemoryRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                seedChatAndLearn(intent)
            }.onFailure { throwable ->
                android.util.Log.e(TAG, "Failed to seed memory chat", throwable)
            }
            pendingResult.finish()
        }
    }

    private suspend fun seedChatAndLearn(intent: Intent) {
        val now = System.currentTimeMillis() / 1000
        val userText = intent.getStringExtra(EXTRA_USER)
            ?: "以后和我聊天别太说教，直接一点，但语气自然一点。"
        val assistantText = intent.getStringExtra(EXTRA_ASSISTANT)
            ?: "明白，我会尽量保持自然、直接，不把回复写成说教。"

        val chatRoom = ChatRoomV2(
            title = "Debug memory seed",
            enabledPlatform = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        val userMessage = MessageV2(
            content = userText,
            platformType = null,
            createdAt = now
        )
        val assistantMessage = MessageV2(
            content = assistantText,
            platformType = DEBUG_PLATFORM_UID,
            createdAt = now + 1
        )

        val savedChatRoom = chatRepository.saveChat(
            chatRoom = chatRoom,
            messages = listOf(userMessage, assistantMessage),
            chatPlatformModels = emptyMap()
        )
        val groupedUserMessages = listOf(userMessage.copy(chatId = savedChatRoom.id))
        val groupedAssistantMessages = listOf(
            listOf(assistantMessage.copy(chatId = savedChatRoom.id))
        )

        memoryRepository.learnFromChat(
            chatRoom = savedChatRoom,
            userMessages = groupedUserMessages,
            assistantMessages = groupedAssistantMessages
        )
        android.util.Log.i(TAG, "Seeded debug chat ${savedChatRoom.id} and requested memory learning")
    }

    companion object {
        private const val TAG = "MemorySeedReceiver"
        private const val DEBUG_PLATFORM_UID = "debug-memory-seed"
        private const val EXTRA_USER = "user"
        private const val EXTRA_ASSISTANT = "assistant"
    }
}
