package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.model.ThemeMode
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineCitationThinkingInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sources = listOf(
        MessageSourceMetadata(
            title = "Reuters: chip makers report record quarter",
            url = "https://www.reuters.com/world/story-1",
            snippet = "Revenue grew 5% year over year.",
            sourceToolName = "web_search"
        ),
        MessageSourceMetadata(
            title = "AP: semiconductor sales climb",
            url = "https://apnews.com/article/abc",
            snippet = "Sales rose across all regions.",
            sourceToolName = "web_search"
        )
    )

    private val answer = "Chip revenue grew 5% this quarter [Reuters](https://reuters.com/world/story-1) " +
        "[AP](https://apnews.com/article/abc). Details are on [the company site](https://example.com/report).\n\n" +
        "Bare links also resolve: https://apnews.com/article/abc"

    @Test
    fun citationLinksRenderAsChipsAndOpenTheirSources() {
        val themeMode = mutableStateOf(ThemeMode.LIGHT)
        composeRule.setContent {
            ChatWithChatTheme(themeMode = themeMode.value) {
                Column(modifier = Modifier.settingsCanvasBackground().padding(16.dp)) {
                    OpponentChatBubble(
                        modifier = Modifier.fillMaxWidth(),
                        canRetry = false,
                        isLoading = false,
                        text = answer,
                        thoughts = "Checking the two articles.",
                        sourceMetadata = sources,
                        contentIdentity = "citations"
                    )
                }
            }
        }
        composeRule.waitForIdle()
        saveScreenshot("citations-light")
        composeRule.runOnIdle { themeMode.value = ThemeMode.DARK }
        composeRule.waitForIdle()
        saveScreenshot("citations-dark")

        composeRule.onNodeWithText("已完成思考").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("引用来源: reuters +1").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reuters: chip makers report record quarter").assertIsDisplayed()
        composeRule.onNodeWithText("AP: semiconductor sales climb").assertIsDisplayed()
    }

    @Test
    fun thinkingHeaderFollowsTheStreamState() {
        val isThinking = mutableStateOf(true)
        // The orb and the label shimmer run until thinking ends; drive the clock by hand so idling
        // does not wait on them.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChatWithChatTheme {
                Column(modifier = Modifier.settingsCanvasBackground().padding(16.dp)) {
                    ThinkingBlockContent(
                        modifier = Modifier.fillMaxWidth(),
                        thoughts = "Weighing the options.",
                        visibleThoughts = "Weighing the options.",
                        isThoughtTextAnimating = false,
                        isThinking = isThinking.value,
                        contentIdentity = "thinking",
                        isLoading = true,
                        isAnswerVisible = false,
                        initiallyExpanded = true
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithText("正在思考").assertIsDisplayed()
        saveScreenshot("thinking-active")

        composeRule.runOnIdle { isThinking.value = false }
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onNodeWithText("已完成思考").assertIsDisplayed()
        saveScreenshot("thinking-done")
    }

    private fun saveScreenshot(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("ui-verify") ?: return
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
    }
}
