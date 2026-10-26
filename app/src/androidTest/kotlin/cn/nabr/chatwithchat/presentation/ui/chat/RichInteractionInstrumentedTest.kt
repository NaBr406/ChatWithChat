package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.nabr.chatwithchat.data.richcontent.ComponentSpec
import cn.nabr.chatwithchat.data.richcontent.RichContentParser
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionAction
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionBinding
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionField
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionFieldType
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionIdentity
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionResult
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionRuntime
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionSnapshot
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionValue
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import cn.nabr.chatwithchat.presentation.ui.chat.richcontent.LocalRichUiComponentRegistry
import cn.nabr.chatwithchat.presentation.ui.chat.richcontent.RichChatContent
import cn.nabr.chatwithchat.presentation.ui.chat.richcontent.RichUiComponentDefinition
import cn.nabr.chatwithchat.presentation.ui.chat.richcontent.RichUiComponentRegistry
import cn.nabr.chatwithchat.presentation.ui.chat.richcontent.RichUiComponentScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RichInteractionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val registry = RichUiComponentRegistry(listOf(FixtureDefinition))
    private val identity = RichInteractionIdentity(1, "turn", "provider", "revision")
    private val source = "<ui-test-control />"

    @Test
    fun rendererEvents_updateLocalState_andReturnDetachedAction() {
        val runtime = RichInteractionRuntime(registry.interactionRegistry)
        val document = RichContentParser(registry.catalog).parse(source, false, 1)
        runtime.sync(identity, document, true)
        val binding = RichInteractionHostBinding(identity, document, checkNotNull(runtime.observe(identity)))
        var action: RichInteractionSnapshot? = null
        val visible = mutableStateOf(true)
        composeRule.setContent {
            ChatWithChatTheme {
                CompositionLocalProvider(LocalRichUiComponentRegistry provides registry) {
                    if (visible.value) {
                        RichChatContent(source, interactionBinding = binding, onInteractionEvent = { id, event ->
                            val result = runtime.dispatch(id, event)
                            if (result is RichInteractionResult.Action) action = result.snapshot
                        })
                    }
                }
            }
        }
        composeRule.onNodeWithText("Toggle: false").performClick()
        composeRule.onNodeWithText("Toggle: true").assertExists()
        composeRule.runOnIdle {
            assertNull(action)
            visible.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.onNodeWithText("Toggle: true").assertExists()
        composeRule.onNodeWithText("Prepare draft").performClick()
        composeRule.runOnIdle {
            val snapshot = checkNotNull(action)
            assertEquals(RichInteractionValue.BooleanValue(true), snapshot.values["selected"])
            assertTrue(runtime.isCurrent(snapshot))
            assertTrue(snapshot.text.contains("\"selected\":true"))
            runtime.sync(identity, document.copy(generation = 2), true)
            assertFalse(runtime.isCurrent(snapshot))
        }
        composeRule.onNodeWithText("Toggle: false").assertExists()
    }

    @Test
    fun streamingOrMismatchedSource_neverEnablesRendererActions() {
        val runtime = RichInteractionRuntime(registry.interactionRegistry)
        val document = RichContentParser(registry.catalog).parse(source, false, 1)
        runtime.sync(identity, document, true)
        val binding = RichInteractionHostBinding(identity, document, checkNotNull(runtime.observe(identity)))
        val streaming = mutableStateOf(true)
        val displayed = mutableStateOf(source)
        var dispatched = false
        composeRule.setContent {
            ChatWithChatTheme {
                CompositionLocalProvider(LocalRichUiComponentRegistry provides registry) {
                    RichChatContent(
                        displayed.value,
                        isStreaming = streaming.value,
                        interactionBinding = binding,
                        onInteractionEvent = { _, _ -> dispatched = true }
                    )
                }
            }
        }
        composeRule.onNodeWithText("Prepare draft").assertIsNotEnabled()
        composeRule.runOnIdle {
            streaming.value = false
            displayed.value = "$source\n\nchanged"
        }
        composeRule.onNodeWithText("Prepare draft").assertIsNotEnabled()
        composeRule.runOnIdle { assertFalse(dispatched) }
    }

    /** Test-only adapter: this is deliberately absent from the production registry and prompt. */
    private object FixtureDefinition : RichUiComponentDefinition {
        override val spec = ComponentSpec("ui-test-control", emptyMap(), markdownChildren = false, selfClosing = true)

        override fun bind(node: RichNode.Component) = RichInteractionBinding(
            fields = listOf(
                RichInteractionField(
                    "selected",
                    RichInteractionFieldType.BooleanType,
                    RichInteractionValue.BooleanValue(false),
                    exportable = true
                )
            ),
            actions = listOf(RichInteractionAction("prepare", "Continue", listOf("selected")))
        )

        @Composable
        override fun Content(scope: RichUiComponentScope) {
            val selected = (scope.state?.values?.get("selected") as? RichInteractionValue.BooleanValue)?.value ?: false
            Column {
                Button(enabled = scope.isEnabled, onClick = {
                    scope.setValue("selected", RichInteractionValue.BooleanValue(!selected))
                }) { Text("Toggle: $selected") }
                Button(enabled = scope.isEnabled, onClick = { scope.invokeAction("prepare") }) { Text("Prepare draft") }
            }
        }
    }
}
