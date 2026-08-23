package cn.nabr.chatwithchat.presentation.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that a running turn is the sole writer of the message list.
 *
 * It reads like an implementation detail and is not one. `_groupedMessages` is both the stream's buffer
 * and the UI's source, and persistence happens once at the end of the turn, so a second writer landing
 * mid-stream is either overwritten by the next published chunk or saved instead of the real answer,
 * depending on timing. The composer already blocked sending and editing; these are the paths that were
 * still reachable — revision navigation on an older turn, and the model menu, which stays enabled by
 * design so the user can pick the next message's model while reading the current answer.
 */
class ChatViewModelTurnGuardTest {

    @Test
    fun `a streaming platform means a turn owns the list`() {
        assertTrue(
            isTurnInFlight(
                loadingStates = listOf(ChatViewModel.LoadingState.Loading),
                isPersistingCompletion = false
            )
        )
    }

    @Test
    fun `one streaming platform among idle ones still counts`() {
        assertTrue(
            isTurnInFlight(
                loadingStates = listOf(
                    ChatViewModel.LoadingState.Idle,
                    ChatViewModel.LoadingState.Loading,
                    ChatViewModel.LoadingState.Idle
                ),
                isPersistingCompletion = false
            )
        )
    }

    /**
     * The window after the stream ends and before `saveChat` returns. Every loading state is already
     * Idle here, so checking those alone would call the list free while it is being written to disk.
     */
    @Test
    fun `the persist window counts even though nothing is loading any more`() {
        assertTrue(
            isTurnInFlight(
                loadingStates = listOf(ChatViewModel.LoadingState.Idle),
                isPersistingCompletion = true
            )
        )
    }

    @Test
    fun `an idle chat leaves the list free`() {
        assertFalse(
            isTurnInFlight(
                loadingStates = listOf(ChatViewModel.LoadingState.Idle, ChatViewModel.LoadingState.Idle),
                isPersistingCompletion = false
            )
        )
    }

    /**
     * A chat that has never sent anything has no loading states at all. `all {}` on an empty list is
     * true, which is what makes `isIdle` correct for a fresh chat — so the inverse must be false here,
     * or a new chat would refuse the first revision flip and the first model change.
     */
    @Test
    fun `a chat that has never sent anything is not in flight`() {
        assertFalse(isTurnInFlight(loadingStates = emptyList(), isPersistingCompletion = false))
    }
}
