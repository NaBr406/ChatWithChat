package cn.nabr.chatwithchat.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollIntentTest {
    @Test
    fun userScrollsAwayFromBottom_entersReadingHistoryWithStableAnchor() {
        val anchor = ChatScrollAnchor(turnKey = "message-4-0", offset = 72)

        val result = reduceChatScrollIntent(
            current = ChatScrollIntent.FollowingLatest,
            event = ChatScrollEvent.UserScrolled(canScrollForward = true, anchor = anchor)
        )

        assertEquals(ChatScrollIntent.ReadingHistory(anchor), result)
    }

    @Test
    fun userReachesBottom_resumesFollowingLatest() {
        val reading = ChatScrollIntent.ReadingHistory(ChatScrollAnchor("message-2-0", 18))

        val result = reduceChatScrollIntent(
            current = reading,
            event = ChatScrollEvent.UserScrolled(canScrollForward = false, anchor = null)
        )

        assertEquals(ChatScrollIntent.FollowingLatest, result)
    }

    @Test
    fun streamCompletion_preservesReadingIntentAndNeverRequestsFollow() {
        val reading = ChatScrollIntent.ReadingHistory(ChatScrollAnchor("message-1-0", 24))

        val result = reduceChatScrollIntent(reading, ChatScrollEvent.StreamCompleted)

        assertEquals(reading, result)
        assertFalse(result.shouldFollowStreaming(isStreaming = false))
    }

    @Test
    fun viewportLayoutChange_doesNotTurnReadingHistoryIntoFollowingLatest() {
        val reading = ChatScrollIntent.ReadingHistory(ChatScrollAnchor("message-1-0", 24))

        val result = reduceChatScrollIntent(reading, ChatScrollEvent.ViewportChanged)

        assertEquals(reading, result)
    }

    @Test
    fun explicitFollowRequest_resumesStreamingFollow() {
        val reading = ChatScrollIntent.ReadingHistory(ChatScrollAnchor("message-3-0", 0))

        val result = reduceChatScrollIntent(reading, ChatScrollEvent.FollowLatestRequested)

        assertTrue(result.shouldFollowStreaming(isStreaming = true))
    }

    @Test
    fun followingLatestRestoresBottomAfterIdleViewportGrowth() {
        assertTrue(
            ChatScrollIntent.FollowingLatest.shouldRestoreFollowingViewport(
                isScrollInProgress = false,
                canScrollForward = true,
                isProgrammaticScrollInProgress = false
            )
        )
    }

    @Test
    fun readingHistoryNeverRestoresBottomAfterViewportGrowth() {
        val reading = ChatScrollIntent.ReadingHistory(ChatScrollAnchor("message-3-0", 0))

        assertFalse(
            reading.shouldRestoreFollowingViewport(
                isScrollInProgress = false,
                canScrollForward = true,
                isProgrammaticScrollInProgress = false
            )
        )
    }

    @Test
    fun persistedTurnKeysSurviveInsertionAndReordering() {
        val registry = ChatTurnKeyRegistry()
        val initialKeys = registry.update(
            listOf(ChatTurnIdentity(11), ChatTurnIdentity(22))
        )

        val updatedKeys = registry.update(
            listOf(ChatTurnIdentity(33), ChatTurnIdentity(22), ChatTurnIdentity(11))
        )

        assertEquals(initialKeys[1], updatedKeys[1])
        assertEquals(initialKeys[0], updatedKeys[2])
    }

    @Test
    fun temporaryTurnKeySurvivesRoomIdAssignment() {
        val registry = ChatTurnKeyRegistry()
        val temporaryKey = registry.update(listOf(ChatTurnIdentity(0))).single()

        val persistedKey = registry.update(listOf(ChatTurnIdentity(42))).single()

        assertEquals(temporaryKey, persistedKey)
    }

    @Test
    fun replacingPersistedTurnAtSamePositionGetsNewKey() {
        val registry = ChatTurnKeyRegistry()
        val firstKey = registry.update(listOf(ChatTurnIdentity(41))).single()

        val replacementKey = registry.update(listOf(ChatTurnIdentity(42))).single()

        assertFalse(firstKey == replacementKey)
    }

    @Test
    fun readingHistoryIntent_survivesSavedStateRoundTrip() {
        val intent = ChatScrollIntent.ReadingHistory(
            ChatScrollAnchor(turnKey = "chat-turn-message-42", offset = 73)
        )

        val restored = chatScrollIntentFromSavedValues(intent.toSavedValues())

        assertEquals(intent, restored)
    }

    @Test
    fun followingLatestIntent_survivesSavedStateRoundTrip() {
        val restored = chatScrollIntentFromSavedValues(
            ChatScrollIntent.FollowingLatest.toSavedValues()
        )

        assertEquals(ChatScrollIntent.FollowingLatest, restored)
    }
}
