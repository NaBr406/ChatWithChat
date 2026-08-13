package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
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
            enabledPlatformLookup = emptyMap()
        )

        assertEquals(RoundStatus.Completed, items.single().status)
        assertTrue(items.single().hasSuccessfulAnswer)
    }

    @Test
    fun selectedCandidateFromSameProviderUsesItsOwnModelAndTokens() {
        val items = buildRoundNavigationItems(
            groupedMessages = ChatViewModel.GroupedMessages(
                userMessages = listOf(MessageV2(content = "compare", platformType = null)),
                assistantMessages = listOf(
                    listOf(
                        MessageV2(
                            content = "model A",
                            platformType = "provider-1",
                            tokenUsage = TokenUsageRecord(
                                inputTokens = 10,
                                outputTokens = 2,
                                platformUid = "provider-1",
                                model = "model-a"
                            )
                        ),
                        MessageV2(
                            content = "model B",
                            platformType = "provider-1",
                            tokenUsage = TokenUsageRecord(
                                inputTokens = 20,
                                outputTokens = 3,
                                platformUid = "provider-1",
                                model = "model-b"
                            )
                        )
                    )
                )
            ),
            loadingStates = emptyList(),
            enabledPlatformLookup = emptyMap(),
            selectedPlatformIndices = listOf(1)
        )

        val item = items.single()
        assertEquals(23, item.totalTokens)
        assertEquals("model-b", item.platformUsages.single().model)
    }
}
