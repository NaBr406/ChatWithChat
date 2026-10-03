package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMarkdownTableInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tableCell_parsesInlineStrongMarkdown() {
        composeRule.setContent {
            ChatWithChatTheme {
                ChatMarkdown(
                    content = """
                    | 肌肉类型 | 特点 |
                    | --- | --- |
                    | **骨骼肌** | 肌质网**发达**，终池**明显** |
                    """.trimIndent()
                )
            }
        }

        composeRule.onNodeWithText("骨骼肌").assertIsDisplayed()
        composeRule.onNodeWithText("肌质网发达，终池明显").assertIsDisplayed()
        composeRule.onAllNodesWithText("肌质网**发达**，终池**明显**").assertCountEquals(0)
    }
}
