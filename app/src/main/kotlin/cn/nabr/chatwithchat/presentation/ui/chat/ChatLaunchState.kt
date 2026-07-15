package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.lifecycle.SavedStateHandle

internal class ChatLaunchState(
    private val savedStateHandle: SavedStateHandle,
    private val routeChatRoomId: Int
) {
    val chatRoomId: Int
        get() = savedStateHandle[PERSISTED_CHAT_ROOM_ID_KEY] ?: routeChatRoomId

    var initialQuestionConsumed: Boolean
        get() = savedStateHandle[INITIAL_QUESTION_CONSUMED_KEY] ?: false
        set(value) {
            savedStateHandle[INITIAL_QUESTION_CONSUMED_KEY] = value
        }

    var initialAttachmentsConsumed: Boolean
        get() = savedStateHandle[INITIAL_ATTACHMENTS_CONSUMED_KEY] ?: false
        set(value) {
            savedStateHandle[INITIAL_ATTACHMENTS_CONSUMED_KEY] = value
        }

    fun recordInitialRequestPersisted() {
        initialQuestionConsumed = true
        initialAttachmentsConsumed = true
    }

    fun recordPersistedChatRoomId(chatRoomId: Int) {
        if (routeChatRoomId == 0 && chatRoomId > 0) {
            savedStateHandle[PERSISTED_CHAT_ROOM_ID_KEY] = chatRoomId
        }
    }

    private companion object {
        const val PERSISTED_CHAT_ROOM_ID_KEY = "chat.launch.persistedChatRoomId"
        const val INITIAL_QUESTION_CONSUMED_KEY = "chat.launch.initialQuestionConsumed"
        const val INITIAL_ATTACHMENTS_CONSUMED_KEY = "chat.launch.initialAttachmentsConsumed"
    }
}

internal fun shouldShowInterruptedInitialRequest(
    initialRequestId: Int,
    assistantContent: String,
    assistantThoughts: String,
    hasAttachments: Boolean,
    isLoading: Boolean
): Boolean = initialRequestId < 0 &&
    !isLoading &&
    assistantContent.isBlank() &&
    assistantThoughts.isBlank() &&
    !hasAttachments
