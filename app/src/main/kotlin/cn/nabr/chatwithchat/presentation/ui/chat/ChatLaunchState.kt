package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.lifecycle.SavedStateHandle
import java.util.UUID

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

    /**
     * Identifies this navigation entry's turns while the chat has no row of its own.
     *
     * Every unsaved chat has id `0`, so keying turns on the id alone would let a second new chat adopt
     * the first one's running turn — reachable by sending, going back before the save, and starting
     * another new chat. Kept in the `SavedStateHandle` because that is what survives view-model
     * recreation within the same entry, which is exactly the case where reconnecting to a live turn has
     * to work. Generated once, lazily: a chat opened from history never needs one.
     */
    val draftTurnNonce: String
        get() = savedStateHandle.get<String>(DRAFT_TURN_NONCE_KEY)
            ?: UUID.randomUUID().toString().also { generated ->
                savedStateHandle[DRAFT_TURN_NONCE_KEY] = generated
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
        const val DRAFT_TURN_NONCE_KEY = "chat.launch.draftTurnNonce"
    }
}

internal fun shouldShowInterruptedInitialRequest(
    initialRequestId: Int,
    assistantContent: String,
    assistantThoughts: String,
    hasAttachments: Boolean,
    hasStickerRefs: Boolean = false,
    isLoading: Boolean
): Boolean = initialRequestId < 0 &&
    !isLoading &&
    assistantContent.isBlank() &&
    assistantThoughts.isBlank() &&
    !hasAttachments &&
    !hasStickerRefs
