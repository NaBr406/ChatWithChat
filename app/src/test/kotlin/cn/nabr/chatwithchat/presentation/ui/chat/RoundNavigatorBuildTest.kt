package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundNavigatorBuildTest {
    @Test
    fun stickerOnlyAssistantReply_isCompletedRound() {
        val items = buildRoundNavigationItems(
            groupedMessages = ChatViewModel.GroupedMessages(
                userMessages = listOf(MessageV2(content = "show me a reaction", platformType = null)),
                assistantMessages = listOf(
                    listOf(
                        MessageV2(
                            content = "",
                            platformType = "openai",
                            stickerRefs = listOf(
                                MessageStickerRef(
                                    instanceId = "tool-call-1",
                                    stickerId = "builtin.reactions.crying_cat",
                                    assetKey = "a".repeat(64),
                                    altText = "小猫痛哭"
                                )
                            )
                        )
                    )
                )
            ),
            loadingStates = emptyList(),
            enabledPlatformsInChat = listOf("openai"),
            enabledPlatformLookup = emptyMap()
        )

        assertEquals(RoundStatus.Completed, items.single().status)
        assertTrue(items.single().hasSuccessfulAnswer)
    }
}
