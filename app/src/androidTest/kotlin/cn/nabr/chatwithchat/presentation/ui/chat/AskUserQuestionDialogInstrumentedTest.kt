package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.tool.AskUserQuestion
import cn.nabr.chatwithchat.data.tool.AskUserQuestionAnswer
import cn.nabr.chatwithchat.data.tool.AskUserQuestionBroker
import cn.nabr.chatwithchat.data.tool.AskUserQuestionOption
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AskUserQuestionDialogInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selectingOptionAndSubmittingReturnsStructuredAnswer() {
        var submitted: List<AskUserQuestionAnswer>? = null
        composeRule.setContent {
            ChatWithChatTheme {
                AskUserQuestionDialog(
                    pending = pendingQuestion(),
                    onSubmit = { answers -> submitted = answers },
                    onCancel = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.ask_user_question_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithText("这次需要修改哪些部分？").assertIsDisplayed()
        composeRule.onNodeWithText("只改界面").performClick()
        composeRule.onNodeWithText(context.getString(R.string.ask_user_question_submit)).performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    AskUserQuestionAnswer(
                        header = "范围",
                        selectedOptions = listOf("只改界面")
                    )
                ),
                submitted
            )
        }
    }

    @Test
    fun chatContentAbovePanelRemainsScrollable() {
        composeRule.setContent {
            ChatWithChatTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("chat-background-list")) {
                        items(40) { index ->
                            Text(
                                text = "聊天消息 $index",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                                    .testTag("chat-message-$index")
                            )
                        }
                    }
                    AskUserQuestionDialog(
                        pending = pendingQuestion(),
                        onSubmit = {},
                        onCancel = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("chat-message-0").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-background-list").performTouchInput {
            swipe(
                start = Offset(centerX, 600f),
                end = Offset(centerX, 100f),
                durationMillis = 500
            )
        }

        composeRule.onNodeWithTag("chat-message-0").assertIsNotDisplayed()
        composeRule.onNodeWithTag("chat-message-3").assertIsDisplayed()
    }

    private fun pendingQuestion(): AskUserQuestionBroker.PendingQuestion = AskUserQuestionBroker.PendingQuestion(
        requestId = "request-1",
        chatId = 1,
        turnKey = "turn-1",
        callId = "call-1",
        questions = listOf(
            AskUserQuestion(
                header = "范围",
                question = "这次需要修改哪些部分？",
                options = listOf(
                    AskUserQuestionOption("只改界面", "只调整 Compose UI"),
                    AskUserQuestionOption("界面和数据层", "同时修改 UI、ViewModel 和数据层")
                )
            )
        )
    )
}
