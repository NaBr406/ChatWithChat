package cn.nabr.chatwithchat.presentation.ui.home

import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerSelectionPolicyTest {
    @Test
    fun resetDrawerSelection_clearsSelectionAndDialog_butPreservesSearchMode() {
        val state = HomeViewModel.ChatListState(
            chats = listOf(chatRoom(id = 1), chatRoom(id = 2)),
            isSelectionMode = true,
            isSearchMode = true,
            selectedChats = listOf(true, false),
            showDeleteWarningDialog = true
        )

        val reset = resetDrawerSelectionState(state)

        assertFalse(reset.isSelectionMode)
        assertTrue(reset.selectedChats.none { it })
        assertFalse(reset.showDeleteWarningDialog)
        assertTrue(reset.isSearchMode)
    }

    @Test
    fun drawerFullyClosed_afterAnyDismissPath_requestsSelectionReset() {
        listOf("scrim", "swipe", "back").forEach { dismissPath ->
            assertTrue(dismissPath, shouldResetSelectionAfterDrawerTransition(wasOpenOrOpening = true, isFullyClosed = true))
        }
    }

    @Test
    fun drawerStillClosing_doesNotResetSelectionEarly() {
        assertFalse(shouldResetSelectionAfterDrawerTransition(wasOpenOrOpening = true, isFullyClosed = false))
    }

    /** A debounced search reload used to reset the selection a long-press had just made. */
    @Test
    fun reloadingChats_keepsSelectionById() {
        val state = HomeViewModel.ChatListState(
            chats = listOf(chatRoom(id = 1), chatRoom(id = 2), chatRoom(id = 3)),
            isSelectionMode = true,
            selectedChats = listOf(false, true, false)
        )

        val reloaded = state.withChats(listOf(chatRoom(id = 3), chatRoom(id = 2)))

        assertEquals(listOf(false, true), reloaded.selectedChats)
        assertTrue(reloaded.isSelectionMode)
    }

    private fun chatRoom(id: Int) = ChatRoomV2(
        id = id,
        title = "Chat $id",
        enabledPlatform = emptyList()
    )
}
