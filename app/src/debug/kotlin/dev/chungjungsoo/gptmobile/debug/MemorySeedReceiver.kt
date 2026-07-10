package dev.chungjungsoo.gptmobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.memory.MemoryCompletedTurnInput
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.MemoryRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
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

    @Inject
    lateinit var settingRepository: SettingRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                seedCompletedTurns(intent)
            }.onFailure { throwable ->
                android.util.Log.e(TAG, "Failed to seed memory chat", throwable)
            }
            pendingResult.finish()
        }
    }

    private suspend fun seedCompletedTurns(intent: Intent) {
        val now = System.currentTimeMillis() / 1000
        val turnCount = intent.getIntExtra(EXTRA_TURN_COUNT, 1).coerceIn(1, 5)
        val platformUids = if (intent.getBooleanExtra(EXTRA_MULTI_PROVIDER, false)) {
            listOf(DEBUG_PLATFORM_UID, SECONDARY_DEBUG_PLATFORM_UID)
        } else {
            listOf(DEBUG_PLATFORM_UID)
        }
        if (intent.hasExtra(EXTRA_MEMORY_ENABLED)) {
            val enabled = intent.getBooleanExtra(EXTRA_MEMORY_ENABLED, false)
            settingRepository.updateMemoryEnabled(enabled)
            memoryRepository.onMemoryEnabledChanged(enabled)
        }
        val memoryEnabled = settingRepository.fetchMemoryEnabled()
        val userText = intent.getStringExtra(EXTRA_USER)
            ?: "以后和我聊天别太说教，直接一点，但语气自然一点。"
        val assistantText = intent.getStringExtra(EXTRA_ASSISTANT)
            ?: "明白，我会尽量保持自然、直接，不把回复写成说教。"

        val chatRoom = ChatRoomV2(
            title = "Debug memory seed",
            enabledPlatform = platformUids,
            createdAt = now,
            updatedAt = now
        )
        val messages = buildList {
            repeat(turnCount) { index ->
                val turnNumber = index + 1
                val turnStartedAt = now + index * TURN_TIMESTAMP_STEP_SECONDS
                add(
                    MessageV2(
                        content = "$userText [debug-turn-$turnNumber]",
                        platformType = null,
                        createdAt = turnStartedAt
                    )
                )
                platformUids.forEachIndexed { platformIndex, platformUid ->
                    add(
                        MessageV2(
                            content = "$assistantText [debug-turn-$turnNumber:$platformUid]",
                            platformType = platformUid,
                            createdAt = turnStartedAt + platformIndex + 1
                        )
                    )
                }
            }
        }

        val savedChatRoom = chatRepository.saveChat(
            chatRoom = chatRoom,
            messages = messages,
            chatPlatformModels = emptyMap()
        )
        val savedMessages = chatRepository.fetchMessagesV2(savedChatRoom.id)
        if (!memoryEnabled) {
            android.util.Log.i(TAG, "Seeded debug chat ${savedChatRoom.id}, memory disabled, recorded=0")
            return
        }

        val savedUserMessages = savedMessages.filter { it.platformType == null }.sortedBy { it.createdAt }
        savedUserMessages.forEachIndexed { index, savedUserMessage ->
            val nextTurnAt = savedUserMessages.getOrNull(index + 1)?.createdAt ?: Long.MAX_VALUE
            val savedAssistantMessages = savedMessages.filter { message ->
                message.platformType != null &&
                    message.createdAt > savedUserMessage.createdAt &&
                    message.createdAt < nextTurnAt
            }
            val result = memoryRepository.recordCompletedTurn(
                MemoryCompletedTurnInput(
                    chatRoom = savedChatRoom,
                    userMessage = savedUserMessage,
                    assistantMessages = savedAssistantMessages,
                    preferredPlatformUid = DEBUG_PLATFORM_UID,
                    stablePlatformOrder = platformUids,
                    completedAt = savedAssistantMessages.maxOfOrNull { it.createdAt } ?: savedUserMessage.createdAt
                )
            )
            android.util.Log.i(
                TAG,
                "Seeded debug chat ${savedChatRoom.id}, turn=${index + 1}/$turnCount, providers=${savedAssistantMessages.size}, " +
                    "recorded=${result.recorded}, pending=${result.pendingCount}"
            )
        }
    }

    companion object {
        private const val TAG = "MemorySeedReceiver"
        private const val DEBUG_PLATFORM_UID = "debug-memory-seed"
        private const val SECONDARY_DEBUG_PLATFORM_UID = "debug-memory-seed-secondary"
        private const val TURN_TIMESTAMP_STEP_SECONDS = 10L
        private const val EXTRA_USER = "user"
        private const val EXTRA_ASSISTANT = "assistant"
        private const val EXTRA_TURN_COUNT = "turn_count"
        private const val EXTRA_MULTI_PROVIDER = "multi_provider"
        private const val EXTRA_MEMORY_ENABLED = "memory_enabled"
    }
}
