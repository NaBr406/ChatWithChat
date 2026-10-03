package cn.nabr.chatwithchat.presentation.common

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullApiUrlCheckboxInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun checkbox_labelClick_togglesOptionalFullUrl() {
        val checked = mutableStateOf(false)
        composeRule.setContent {
            ChatWithChatTheme {
                FullApiUrlCheckbox(checked = checked.value, onCheckedChange = { checked.value = it })
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.use_full_api_url)
        composeRule.onNodeWithText(label).assertIsOff().performClick().assertIsOn().performClick().assertIsOff()
    }

    @Test
    fun checkbox_disabled_cannotToggle() {
        composeRule.setContent {
            ChatWithChatTheme {
                FullApiUrlCheckbox(checked = false, onCheckedChange = {}, enabled = false)
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.use_full_api_url)
        composeRule.onNodeWithText(label).assertIsNotEnabled().assertIsOff()
    }
}
