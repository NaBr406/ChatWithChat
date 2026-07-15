package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLaunchStateTest {
    @Test
    fun restoredNewChat_usesPersistedRoomAndDoesNotReplayInitialRequest() {
        val savedStateHandle = SavedStateHandle()
        val launchState = ChatLaunchState(savedStateHandle, routeChatRoomId = 0)

        launchState.initialQuestionConsumed = true
        launchState.initialAttachmentsConsumed = true
        launchState.recordPersistedChatRoomId(42)

        val restoredState = ChatLaunchState(savedStateHandle, routeChatRoomId = 0)

        assertEquals(42, restoredState.chatRoomId)
        assertTrue(restoredState.initialQuestionConsumed)
        assertTrue(restoredState.initialAttachmentsConsumed)
    }

    @Test
    fun existingChat_keepsRouteRoomAndStartsWithUnconsumedRequestState() {
        val launchState = ChatLaunchState(SavedStateHandle(), routeChatRoomId = 17)

        launchState.recordPersistedChatRoomId(42)

        assertEquals(17, launchState.chatRoomId)
        assertFalse(launchState.initialQuestionConsumed)
        assertFalse(launchState.initialAttachmentsConsumed)
    }

    @Test
    fun initialRequest_isConsumedOnlyAfterPersistenceIsRecorded() {
        val launchState = ChatLaunchState(SavedStateHandle(), routeChatRoomId = 0)

        assertFalse(launchState.initialQuestionConsumed)
        assertFalse(launchState.initialAttachmentsConsumed)

        launchState.recordInitialRequestPersisted()

        assertTrue(launchState.initialQuestionConsumed)
        assertTrue(launchState.initialAttachmentsConsumed)
    }

    @Test
    fun initialRequestRecovery_requiresStableMarkerAndInitialPayload() {
        assertTrue(
            shouldRecoverInitialRequest(
                routeChatRoomId = 0,
                initialRequestId = -42,
                initialQuestion = "hello",
                initialAttachmentPaths = emptyList()
            )
        )
        assertFalse(
            shouldRecoverInitialRequest(
                routeChatRoomId = 0,
                initialRequestId = 0,
                initialQuestion = "hello",
                initialAttachmentPaths = emptyList()
            )
        )
        assertFalse(
            shouldRecoverInitialRequest(
                routeChatRoomId = 17,
                initialRequestId = -42,
                initialQuestion = "hello",
                initialAttachmentPaths = emptyList()
            )
        )
    }

    @Test
    fun recoveredInitialRequest_withoutAssistantPayload_isVisibleAndRetryable() {
        assertTrue(
            shouldShowInterruptedInitialRequest(
                initialRequestId = -42,
                assistantContent = "",
                assistantThoughts = "",
                hasAttachments = false,
                isLoading = false
            )
        )
        assertFalse(
            shouldShowInterruptedInitialRequest(
                initialRequestId = -42,
                assistantContent = "answer",
                assistantThoughts = "",
                hasAttachments = false,
                isLoading = false
            )
        )
        assertFalse(
            shouldShowInterruptedInitialRequest(
                initialRequestId = -42,
                assistantContent = "",
                assistantThoughts = "",
                hasAttachments = false,
                isLoading = true
            )
        )
    }
}
