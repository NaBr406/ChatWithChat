package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import cn.nabr.chatwithchat.presentation.ui.chat.richcontent.RichChatContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingTextRevealInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun append_keepsLinksCopyAndTableInteractionAvailable() {
        val content = mutableStateOf("Before")
        val streaming = mutableStateOf(true)
        var openedUrl: String? = null
        composeRule.setContent {
            ChatWithChatTheme {
                CompositionLocalProvider(
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) {
                            openedUrl = uri
                        }
                    }
                ) {
                    ChatMarkdown(content.value, contentIdentity = "stream", isStreaming = streaming.value)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            content.value = "Before [link](https://example.com)\n\n```kotlin\nval value = 1\n```\n\n| a | b | c | d |\n|---|---|---|---|\n| one | two | three | four |"
        }
        composeRule.waitForIdle()
        val link = composeRule.onNodeWithText("Before link")
        val layouts = mutableListOf<TextLayoutResult>()
        link.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val position = layouts.single().getBoundingBox(8).center
        link.performTouchInput { click(position) }
        composeRule.runOnIdle { assertEquals("https://example.com", openedUrl) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy_code)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.code_copied)).assertIsDisplayed()
        composeRule.onNodeWithText("val value = 1").assertIsDisplayed()
        composeRule.onNodeWithText("a").assertIsDisplayed()
        val scrolls = composeRule.onAllNodes(hasScrollAction())
        val tableScroll = scrolls[scrolls.fetchSemanticsNodes().lastIndex]
        tableScroll.performTouchInput { swipeLeft() }
        val scrolled = tableScroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        org.junit.Assert.assertTrue(scrolled > 0f)
        composeRule.runOnIdle { streaming.value = false }
        composeRule.waitForIdle()
        assertEquals(scrolled, tableScroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value())
        composeRule.onNodeWithText("val value = 1").assertIsDisplayed()
    }

    @Test
    fun append_longTextAndRichMarkdownKeepFullSemantics() {
        val body = "正文".repeat(2_100)
        val content = mutableStateOf("old")
        composeRule.setContent {
            ChatWithChatTheme { RichChatContent(content.value, contentIdentity = "rich", isStreaming = true) }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { content.value = "$body\n\n<ui-card title=\"Card\">child</ui-card>" }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(body).assertExists()
        composeRule.runOnIdle { content.value += "\n\nnew 👩" }
        composeRule.waitForIdle()
        composeRule.runOnIdle { content.value += "‍💻 e\u0301" }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("new 👩‍💻 e\u0301").assertExists()
        assertEquals(5, streamingRevealStart("👩‍💻", 2))
    }

    @Test
    fun thinkingAppend_preservesPendingPromotionLinksCopyAndCollapse() {
        val thoughts = mutableStateOf("Old thought")
        val streaming = mutableStateOf(true)
        var openedUrl: String? = null
        composeRule.setContent {
            ChatWithChatTheme {
                CompositionLocalProvider(
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) {
                            openedUrl = uri
                        }
                    }
                ) {
                    ThinkingBlockContent(
                        modifier = Modifier,
                        thoughts = thoughts.value,
                        visibleThoughts = thoughts.value,
                        isThoughtTextAnimating = streaming.value,
                        // Header state is covered by InlineCitationThinkingInstrumentedTest; keeping it
                        // settled here leaves this test free of the never-ending thinking animation.
                        isThinking = false,
                        contentIdentity = "thinking-stream",
                        isLoading = streaming.value,
                        isAnswerVisible = false,
                        initiallyExpanded = true
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { thoughts.value += " new text" }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Old thought new text●").assertIsDisplayed()
        composeRule.runOnIdle {
            thoughts.value += "\n\n[link](https://example.com)\n\n```kotlin\nval thought = 1\n```\n\nNext pending"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Old thought new text").assertIsDisplayed()
        composeRule.onNodeWithText("Next pending●").assertIsDisplayed()
        val link = composeRule.onNodeWithText("link")
        val layouts = mutableListOf<TextLayoutResult>()
        link.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        link.performTouchInput { click(layouts.single().getBoundingBox(1).center) }
        composeRule.runOnIdle { assertEquals("https://example.com", openedUrl) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy_code)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.code_copied)).assertIsDisplayed()
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG).assertDoesNotExist()
        composeRule.runOnIdle { thoughts.value += " while hidden" }
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Next pending while hidden●").assertIsDisplayed()
        composeRule.runOnIdle { streaming.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Next pending while hidden").assertIsDisplayed()
        composeRule.onNodeWithText("val thought = 1").assertIsDisplayed()
    }

    @Test
    fun opponentBubble_streamingThoughtsWorkWithSmoothingDisabled() {
        val thoughts = mutableStateOf("Initial thought")
        val answer = mutableStateOf("")
        val streaming = mutableStateOf(true)
        composeRule.setContent {
            ChatWithChatTheme {
                OpponentChatBubble(
                    canRetry = false,
                    isLoading = streaming.value,
                    text = answer.value,
                    thoughts = thoughts.value,
                    contentIdentity = "production-thinking",
                    canShowActions = false
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { thoughts.value += " new thought" }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Initial thought new thought").assertIsDisplayed()
        composeRule.runOnIdle {
            thoughts.value += "\n\n```kotlin\nval reasoning = 2\n```\n\nMore reasoning"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("val reasoning = 2").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy_code)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.code_copied)).assertIsDisplayed()
        composeRule.runOnIdle {
            answer.value = "Final answer"
            streaming.value = false
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("More reasoning").assertIsDisplayed()
        composeRule.onNodeWithText("Final answer").assertIsDisplayed()
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THINKING_CONTENT_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Final answer").assertIsDisplayed()
    }
}
