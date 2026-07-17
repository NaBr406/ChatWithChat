package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThinkingBlockInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun streamingThinking_startsExpanded() {
        composeRule.setContent {
            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = "Analyzing the request",
                    contentIdentity = "streaming",
                    isLoading = true
                )
            }
        }

        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG)
            .assertTextContains("思考中")
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG)
            .assertIsDisplayed()
    }

    @Test
    fun userCollapse_survivesThoughtChunksAndCompletion() {
        composeRule.mainClock.autoAdvance = false
        lateinit var updateThoughts: (String) -> Unit
        lateinit var finishStreaming: () -> Unit
        composeRule.setContent {
            var thoughts by remember { mutableStateOf("First chunk") }
            var isLoading by remember { mutableStateOf(true) }
            updateThoughts = { thoughts = it }
            finishStreaming = { isLoading = false }

            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = thoughts,
                    contentIdentity = "stable-stream",
                    isLoading = isLoading
                )
            }
        }

        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(180)
        composeRule.onAllNodesWithTag(THINKING_CONTENT_TEST_TAG).assertCountEquals(0)

        composeRule.runOnIdle {
            updateThoughts("First chunk followed by a second chunk")
            finishStreaming()
        }

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG)
            .assertTextContains("已思考")
        composeRule.onAllNodesWithTag(THINKING_CONTENT_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun completedHistory_startsCollapsedAndCanExpand() {
        composeRule.setContent {
            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = "Completed reasoning",
                    contentIdentity = "history",
                    isLoading = false
                )
            }
        }

        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG)
            .assertTextContains("已思考")
        composeRule.onAllNodesWithTag(THINKING_CONTENT_TEST_TAG).assertCountEquals(0)

        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG).performClick()
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun activeResponseAfterThinkingPhase_startsExpanded() {
        composeRule.setContent {
            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = "Completed reasoning before the answer",
                    contentIdentity = "active-answer",
                    isLoading = false,
                    initiallyExpanded = true
                )
            }
        }

        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG)
            .assertTextContains("已思考")
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun answerPhase_finishesThoughtPresentationWhileResponseStillStreams() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = "reasoning ".repeat(2_000),
                    contentIdentity = "answer-phase",
                    isLoading = true,
                    isThinking = false,
                    initiallyExpanded = true
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG)
            .assertTextContains("思考中")

        composeRule.mainClock.advanceTimeBy(2_200)
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG)
            .assertTextContains("已思考")
    }

    @Test
    fun userCollapse_keepsContentMountedUntilExitAnimationCompletes() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = "Animated reasoning content",
                    contentIdentity = "animated-collapse",
                    isLoading = false,
                    initiallyExpanded = true
                )
            }
        }

        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(60)
        composeRule.onAllNodesWithTag(THINKING_CONTENT_TEST_TAG).assertCountEquals(1)

        composeRule.mainClock.advanceTimeBy(160)
        composeRule.onAllNodesWithTag(THINKING_CONTENT_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun streamCompletion_keepsPacingInsteadOfDumpingTheBacklog() {
        composeRule.mainClock.autoAdvance = false
        val target = "reasoning ".repeat(2_000)
        lateinit var finishStreaming: () -> Unit
        composeRule.setContent {
            var isStreaming by remember { mutableStateOf(true) }
            finishStreaming = { isStreaming = false }
            Text(
                text = rememberSmoothedStreamingText(
                    targetText = target,
                    isStreaming = isStreaming,
                    contentIdentity = "bounded-drain"
                ),
                modifier = Modifier.testTag(STREAMING_TEXT_TEST_TAG)
            )
        }

        composeRule.mainClock.advanceTimeBy(48)
        assertTrue(displayedStreamingText().length in 1 until target.length)

        val lengthBeforeCompletion = displayedStreamingText().length
        composeRule.runOnIdle { finishStreaming() }
        composeRule.mainClock.advanceTimeByFrame()
        val firstCompletedFrameLength = displayedStreamingText().length
        assertTrue(firstCompletedFrameLength - lengthBeforeCompletion in 1..16)

        composeRule.mainClock.advanceTimeBy(300)
        assertTrue(displayedStreamingText().length < target.length)

        composeRule.mainClock.advanceTimeBy(2_200)
        assertEquals(target, displayedStreamingText())
    }

    private fun displayedStreamingText(): String = composeRule
        .onNodeWithTag(STREAMING_TEXT_TEST_TAG)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(separator = "") { it.text }

    private companion object {
        const val STREAMING_TEXT_TEST_TAG = "streaming-text"
    }
}
