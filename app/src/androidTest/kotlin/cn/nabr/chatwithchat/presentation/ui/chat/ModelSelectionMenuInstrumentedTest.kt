package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.model.ReasoningCapability
import cn.nabr.chatwithchat.data.model.ReasoningCapabilityProfile
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelSelectionMenuInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun triggerClick_mountsPopupOnTheNextAnimationFrame() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChatWithChatTheme {
                ModelSelectionMenu(
                    label = "Current model",
                    options = listOf(
                        ModelSelectionOption(
                            platformUid = "provider",
                            label = "Model A",
                            model = "model-a",
                            selected = true
                        )
                    ),
                    selectedReasoningMode = ReasoningMode.AUTO,
                    enabled = true,
                    onOptionSelected = {},
                    onReasoningModeSelected = {}
                )
            }
        }

        composeRule.onNodeWithText("Current model").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodesWithText("Model A").assertCountEquals(1)
    }

    @Test
    fun modelClick_invokesSelectionBeforeAnimationAdvances() {
        composeRule.mainClock.autoAdvance = false
        var selectedModel: String? = null
        composeRule.setContent {
            ChatWithChatTheme {
                ModelSelectionMenu(
                    label = "Current model",
                    options = listOf(
                        ModelSelectionOption(
                            platformUid = "provider",
                            label = "Model A",
                            model = "model-a",
                            selected = true
                        ),
                        ModelSelectionOption(
                            platformUid = "provider",
                            label = "Model B",
                            model = "model-b"
                        )
                    ),
                    selectedReasoningMode = ReasoningMode.AUTO,
                    enabled = true,
                    onOptionSelected = { option -> selectedModel = option.model },
                    onReasoningModeSelected = {}
                )
            }
        }

        composeRule.onNodeWithText("Current model").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Model B").performClick()

        composeRule.runOnIdle {
            assertEquals("model-b", selectedModel)
        }
    }

    @Test
    fun controlledDismiss_keepsPopupMountedUntilExitAnimationCompletes() {
        composeRule.mainClock.autoAdvance = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reasoningLabel = context.getString(R.string.reasoning_mode)
        val lowReasoningLabel = context.getString(R.string.reasoning_mode_low)
        composeRule.setContent {
            ChatWithChatTheme {
                ModelSelectionMenu(
                    label = "Current model",
                    options = listOf(
                        ModelSelectionOption(
                            platformUid = "provider",
                            label = "Model A",
                            model = "model-a",
                            selected = true,
                            reasoningCapability = ReasoningCapabilityProfile(
                                capability = ReasoningCapability.EFFORT,
                                supportedModes = listOf(ReasoningMode.AUTO, ReasoningMode.LOW)
                            )
                        )
                    ),
                    selectedReasoningMode = ReasoningMode.AUTO,
                    enabled = true,
                    onOptionSelected = {},
                    onReasoningModeSelected = {}
                )
            }
        }

        composeRule.onNodeWithText("Current model").performClick()
        composeRule.mainClock.advanceTimeBy(220)
        composeRule.onNodeWithText("Model A").assertIsDisplayed()

        composeRule.onNodeWithText(reasoningLabel).performClick()
        composeRule.mainClock.advanceTimeBy(180)
        composeRule.onNodeWithText(lowReasoningLabel).performClick()
        composeRule.mainClock.advanceTimeBy(60)
        composeRule.onAllNodesWithText("Model A").assertCountEquals(1)

        composeRule.mainClock.advanceTimeBy(160)
        composeRule.onAllNodesWithText("Model A").assertCountEquals(0)
    }
}
