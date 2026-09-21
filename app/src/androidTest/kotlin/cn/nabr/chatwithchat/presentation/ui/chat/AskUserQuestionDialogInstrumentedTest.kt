package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
