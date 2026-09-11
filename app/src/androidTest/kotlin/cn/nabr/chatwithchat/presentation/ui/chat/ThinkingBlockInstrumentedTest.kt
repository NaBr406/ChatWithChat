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
import androidx.compose.ui.test.onNodeWithText
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
    fun preloadedStreamingText_isVisibleWithoutReplay() {
        val alreadyReceived = "already received ".repeat(50)

        composeRule.setContent {
            Text(
                text = rememberSmoothedStreamingText(
                    targetText = alreadyReceived,
                    isStreaming = true,
                    contentIdentity = "preloaded-stream"
                ),
                modifier = Modifier.testTag(STREAMING_TEXT_TEST_TAG)
            )
        }

        composeRule.mainClock.advanceTimeBy(48)
        assertEquals(alreadyReceived, displayedStreamingText())
    }

    @Test
    fun thinkingBacklog_holdsTextUntilThoughtsFinishRevealing() {
        composeRule.mainClock.autoAdvance = false
        val thoughts = "reasoning ".repeat(20)
        val text = "answer ".repeat(500)
        lateinit var publishBacklog: () -> Unit
        composeRule.setContent {
            var thoughtsState by remember { mutableStateOf("") }
            var textState by remember { mutableStateOf("") }
            publishBacklog = {
                thoughtsState = thoughts
                textState = text
            }
            val stream = rememberSerializedStreamingText(
                targetText = textState,
                targetThoughts = thoughtsState,
                isStreaming = true,
                contentIdentity = "serialized-thinking-first"
            )
            Text(
                text = stream.thoughts,
                modifier = Modifier.testTag(THINKING_STREAM_TEST_TAG)
            )
            Text(
                text = stream.text,
                modifier = Modifier.testTag(STREAMING_TEXT_TEST_TAG)
            )
        }

        composeRule.mainClock.advanceTimeByFrame()
        assertEquals("", displayedStreamingText())
        composeRule.runOnIdle(publishBacklog)
        composeRule.mainClock.advanceTimeBy(48)
        assertTrue(displayedThinkingText().isNotEmpty())
        assertEquals("", displayedStreamingText())

        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals(thoughts, displayedThinkingText())
        assertTrue(displayedStreamingText().isNotEmpty())
        assertTrue(displayedStreamingText().length < text.length)
    }

    @Test
    fun resumedThinking_pausesTextUntilTheThoughtBacklogIsRevealed() {
        composeRule.mainClock.autoAdvance = false
        val text = "answer ".repeat(500)
        val thoughts = "reasoning ".repeat(20)
        lateinit var publishText: () -> Unit
        lateinit var publishThoughts: () -> Unit
        composeRule.setContent {
            var thoughtsState by remember { mutableStateOf("") }
            var textState by remember { mutableStateOf("") }
            publishText = { textState = text }
            publishThoughts = { thoughtsState = thoughts }
            val stream = rememberSerializedStreamingText(
                targetText = textState,
                targetThoughts = thoughtsState,
                isStreaming = true,
                contentIdentity = "serialized-thought-resume"
            )
            Text(
                text = stream.thoughts,
                modifier = Modifier.testTag(THINKING_STREAM_TEST_TAG)
            )
            Text(
                text = stream.text,
                modifier = Modifier.testTag(STREAMING_TEXT_TEST_TAG)
            )
        }

        composeRule.runOnIdle(publishText)
        composeRule.mainClock.advanceTimeBy(240)
        val textLengthBeforeThinking = displayedStreamingText().length
        assertTrue(textLengthBeforeThinking > 0)

        composeRule.runOnIdle(publishThoughts)
        composeRule.mainClock.advanceTimeBy(48)
        assertTrue(displayedThinkingText().isNotEmpty())
        assertEquals(textLengthBeforeThinking, displayedStreamingText().length)

        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals(thoughts, displayedThinkingText())
        assertTrue(displayedStreamingText().length > textLengthBeforeThinking)
        assertTrue(displayedStreamingText().length < text.length)
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
    fun completedThinking_rendersFencedCodeContent() {
        composeRule.setContent {
            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = """
                        Before the code block.

                        ```kotlin
                        val answer = 42
                        ```
                    """.trimIndent(),
                    contentIdentity = "history-code-block",
                    isLoading = false,
                    initiallyExpanded = true
                )
            }
        }

        composeRule.onNodeWithText("val answer = 42", substring = true).assertIsDisplayed()
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
    fun preloadedThoughts_doNotReplayWhileResponseStillStreams() {
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
            .assertTextContains("已思考")
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG)
            .assertIsDisplayed()
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
        val initial = "reasoning ".repeat(20)
        val target = "reasoning ".repeat(2_000)
        lateinit var pushBacklog: () -> Unit
        lateinit var finishStreaming: () -> Unit
        composeRule.setContent {
            var isStreaming by remember { mutableStateOf(true) }
            var text by remember { mutableStateOf(initial) }
            pushBacklog = { text = target }
            finishStreaming = { isStreaming = false }
            Text(
                text = rememberSmoothedStreamingText(
                    targetText = text,
                    isStreaming = isStreaming,
                    contentIdentity = "bounded-drain"
                ),
                modifier = Modifier.testTag(STREAMING_TEXT_TEST_TAG)
            )
        }

        composeRule.mainClock.advanceTimeBy(48)
        assertEquals(initial, displayedStreamingText())

        val lengthBeforeCompletion = displayedStreamingText().length
        composeRule.runOnIdle {
            pushBacklog()
            finishStreaming()
        }
        composeRule.mainClock.advanceTimeByFrame()
        val firstCompletedFrameLength = displayedStreamingText().length
        assertTrue(firstCompletedFrameLength - lengthBeforeCompletion in 1..16)

        composeRule.mainClock.advanceTimeBy(300)
        assertTrue(displayedStreamingText().length < target.length)

        composeRule.mainClock.advanceTimeBy(2_200)
        assertEquals(target, displayedStreamingText())
    }

    @Test
    fun resumedThinking_returnsToTheInProgressHeader() {
        composeRule.mainClock.autoAdvance = false
        lateinit var resumeThinking: () -> Unit
        composeRule.setContent {
            var thoughts by remember { mutableStateOf("First round reasoning") }
            var isThinking by remember { mutableStateOf(false) }
            resumeThinking = {
                thoughts = "First round reasoning\n\nSecond round reasoning"
                isThinking = true
            }

            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = thoughts,
                    contentIdentity = "resumed-thinking",
                    isLoading = true,
                    isThinking = isThinking,
                    initiallyExpanded = true
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(2_500)
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG)
            .assertTextContains("已思考")

        // 分步执行的下一轮推理回来了，表头必须跟着回到"思考中"。
        composeRule.runOnIdle { resumeThinking() }
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG)
            .assertTextContains("思考中")
    }

    @Test
    fun resumedStreaming_revealsTheNewTailGraduallyInsteadOfSnapping() {
        composeRule.mainClock.autoAdvance = false
        val firstSegment = "reasoning ".repeat(20)
        val resumedTarget = firstSegment + "resumed ".repeat(400)
        lateinit var pauseStreaming: () -> Unit
        lateinit var resumeStreaming: () -> Unit
        composeRule.setContent {
            var isStreaming by remember { mutableStateOf(true) }
            var target by remember { mutableStateOf(firstSegment) }
            pauseStreaming = { isStreaming = false }
            resumeStreaming = {
                target = resumedTarget
                isStreaming = true
            }

            Text(
                text = rememberSmoothedStreamingText(
                    targetText = target,
                    isStreaming = isStreaming,
                    contentIdentity = "resumed-drain"
                ),
                modifier = Modifier.testTag(STREAMING_TEXT_TEST_TAG)
            )
        }

        composeRule.mainClock.advanceTimeBy(3_000)
        assertEquals(firstSegment, displayedStreamingText())

        // 排空一次：旧实现的动画循环在这里就永久退出了。
        composeRule.runOnIdle { pauseStreaming() }
        composeRule.mainClock.advanceTimeBy(3_000)
        assertEquals(firstSegment, displayedStreamingText())

        composeRule.runOnIdle { resumeStreaming() }
        composeRule.mainClock.advanceTimeByFrame()
        val resumedLength = displayedStreamingText().length
        assertTrue(resumedLength > firstSegment.length)
        assertTrue(resumedLength < resumedTarget.length)
    }

    @Test
    fun answerArrival_keepsThinkingExpanded() {
        composeRule.mainClock.autoAdvance = false
        lateinit var startAnswer: () -> Unit
        composeRule.setContent {
            var isAnswerVisible by remember { mutableStateOf(false) }
            startAnswer = { isAnswerVisible = true }

            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = "First round reasoning",
                    contentIdentity = "auto-collapse",
                    isLoading = true,
                    isAnswerVisible = isAnswerVisible,
                    initiallyExpanded = true
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG).assertIsDisplayed()

        // 正文到达不能再改变用户当前正在阅读的思考块状态。
        composeRule.runOnIdle { startAnswer() }
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun answerArrival_keepsThinkingWithCodeExpanded() {
        lateinit var showAnswer: () -> Unit
        composeRule.setContent {
            var isAnswerVisible by remember { mutableStateOf(false) }
            showAnswer = { isAnswerVisible = true }

            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = """
                        Checking the implementation.

                        ```kotlin
                        val answer = 42
                        ```
                    """.trimIndent(),
                    contentIdentity = "code-answer-phase",
                    isLoading = true,
                    isThinking = false,
                    isAnswerVisible = isAnswerVisible,
                    initiallyExpanded = true
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle(showAnswer)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("val answer = 42", substring = true).assertIsDisplayed()
    }

    @Test
    fun answerAlreadyVisible_startsCollapsedWithoutFlashing() {
        composeRule.setContent {
            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = "Reasoning that arrived after the prose",
                    contentIdentity = "prose-first",
                    isLoading = true,
                    isAnswerVisible = true,
                    initiallyExpanded = true
                )
            }
        }

        composeRule.onAllNodesWithTag(THINKING_CONTENT_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun userExpansion_survivesTheAnswerArriving() {
        composeRule.mainClock.autoAdvance = false
        lateinit var startAnswer: () -> Unit
        composeRule.setContent {
            var isAnswerVisible by remember { mutableStateOf(false) }
            startAnswer = { isAnswerVisible = true }

            ChatWithChatTheme {
                ThinkingBlock(
                    thoughts = "First round reasoning",
                    contentIdentity = "manual-expansion",
                    isLoading = true,
                    isAnswerVisible = isAnswerVisible,
                    initiallyExpanded = false
                )
            }
        }

        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG).assertIsDisplayed()

        // 用户自己展开的，正文到来不能替他收起。
        composeRule.runOnIdle { startAnswer() }
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG).assertIsDisplayed()
    }

    private fun displayedStreamingText(): String = composeRule
        .onNodeWithTag(STREAMING_TEXT_TEST_TAG)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(separator = "") { it.text }

    private fun displayedThinkingText(): String = composeRule
        .onNodeWithTag(THINKING_STREAM_TEST_TAG)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(separator = "") { it.text }

    private companion object {
        const val STREAMING_TEXT_TEST_TAG = "streaming-text"
        const val THINKING_STREAM_TEST_TAG = "thinking-streaming-text"
    }
}
