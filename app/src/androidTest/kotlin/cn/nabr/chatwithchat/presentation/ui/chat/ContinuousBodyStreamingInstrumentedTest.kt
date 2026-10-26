package cn.nabr.chatwithchat.presentation.ui.chat

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import cn.nabr.chatwithchat.presentation.ui.chat.richcontent.RichChatContent
import cn.nabr.chatwithchat.util.handleStates
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinuousBodyStreamingInstrumentedTest {
    // Markdown parses on Default. Queue its state updates for the test clock instead of
    // allowing the default unconfined test dispatcher to resume Android layout on that worker.
    @get:Rule
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher())

    @Test
    fun markdown_continuousChunks_renderBeforeStreamFinishes() {
        assertRendersDuringContinuousStream { body, _, streaming ->
            ChatMarkdown(body, contentIdentity = "continuous-markdown", isStreaming = streaming)
        }
    }

    @Test
    fun richMarkdown_continuousChunks_renderBeforeStreamFinishes() {
        assertRendersDuringContinuousStream { body, _, streaming ->
            RichChatContent(
                content = "<ui-card title=\"Stream card\">Fixed child</ui-card>\n\n$body",
                contentIdentity = "continuous-rich-markdown",
                isStreaming = streaming
            )
        }
        composeRule.onNodeWithText("Stream card").assertIsDisplayed()
    }

    @Test
    fun opponentBubble_thinkingThenContinuousBody_rendersBeforeStreamFinishes() {
        assertRendersDuringContinuousStream(captureStreamingScreenshot = true) { body, thoughts, streaming ->
            OpponentChatBubble(
                canRetry = false,
                canShowActions = false,
                isLoading = streaming,
                text = body,
                thoughts = thoughts,
                contentIdentity = "continuous-thinking-to-body"
            )
        }
        composeRule.onNodeWithTag(THINKING_HEADER_TEST_TAG).assertExists()
    }

    private fun assertRendersDuringContinuousStream(
        captureStreamingScreenshot: Boolean = false,
        render: @Composable (String, String, Boolean) -> Unit
    ) {
        val messages = MutableStateFlow(
            ChatViewModel.GroupedMessages(
                userMessages = listOf(MessageV2(content = "Hello", platformType = null)),
                assistantMessages = listOf(listOf(MessageV2(content = "", platformType = "mock-provider")))
            )
        )
        val streaming = mutableStateOf(true)
        val providerChunks = Channel<ApiState>(Channel.UNLIMITED)
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val collector = scope.launch {
            providerChunks.receiveAsFlow().handleStates(
                messageFlow = messages,
                turnIndex = 0,
                platformIdx = 0,
                onLoadingComplete = { streaming.value = false }
            )
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handler = Handler(Looper.getMainLooper())
        val emittedChunks = AtomicInteger()
        lateinit var semanticsRoot: RootForTest
        composeRule.setContent {
            val root = LocalView.current as RootForTest
            SideEffect { semanticsRoot = root }
            val grouped by messages.collectAsState()
            val reply = grouped.assistantMessages.single().single()
            ChatWithChatTheme { render(reply.content, reply.thoughts, streaming.value) }
        }
        composeRule.waitForIdle()

        // The producer uses wall time and keeps running while semantics are queried. Waiting for
        // idle between individual chunks would allow a debounced renderer to pass this test.
        val producer = object : Runnable {
            override fun run() {
                val index = emittedChunks.incrementAndGet()
                if (index <= THINKING_CHUNKS) {
                    providerChunks.trySend(ApiState.Thinking("Thinking step$index "))
                } else {
                    val prefix = if (index == THINKING_CHUNKS + 1) BODY_PREFIX else ""
                    providerChunks.trySend(ApiState.Success("${prefix}part$index "))
                }
                handler.postDelayed(this, CHUNK_INTERVAL_MILLIS)
            }
        }

        instrumentation.runOnMainSync { handler.post(producer) }
        try {
            var previousText = ""
            var previousChunkCount = 0
            repeat(3) {
                var renderedText = ""
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    // Standard semantics queries wait for global idle, which a continuous
                    // producer need never reach. Inspect the real, laid-out tree on Main instead.
                    instrumentation.runOnMainSync {
                        semanticsRoot.measureAndLayoutForTest()
                        val rootBounds = semanticsRoot.semanticsOwner.rootSemanticsNode.boundsInRoot
                        renderedText = semanticsRoot.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                            .filter { node -> node.layoutInfo.isPlaced && !node.boundsInRoot.intersect(rootBounds).isEmpty }
                            .mapNotNull { node ->
                                val layouts = mutableListOf<TextLayoutResult>()
                                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                                layouts.firstOrNull()?.layoutInput?.text?.text
                            }.firstOrNull { it.startsWith(BODY_PREFIX) }.orEmpty()
                    }
                    renderedText.length >= previousText.length + 24
                }
                val chunkCount = emittedChunks.get()
                assertTrue("The producer must keep emitting between observations", chunkCount > previousChunkCount)
                assertTrue("Already rendered text must survive later chunks", renderedText.startsWith(previousText))
                assertTrue("The reply must still be streaming at each observation", streaming.value)
                assertTrue("The provider Flow must remain active before Done", collector.isActive)
                previousText = renderedText
                previousChunkCount = chunkCount
            }
            if (captureStreamingScreenshot) {
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
                    File(directory, "body-streaming-during-provider.png").outputStream().use { output ->
                        assertTrue("The streaming screenshot must be written", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                    assertTrue("The provider must still be active in the screenshot", collector.isActive && streaming.value)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                handler.removeCallbacks(producer)
                providerChunks.trySend(ApiState.Success(FINAL_MARKER))
                providerChunks.trySend(ApiState.Done)
                providerChunks.close()
            }
            try {
                runBlocking { withTimeout(5_000) { collector.join() } }
            } finally {
                scope.cancel()
            }
        }
        composeRule.onNodeWithText(FINAL_MARKER, substring = true).assertIsDisplayed()
    }

    private companion object {
        const val BODY_PREFIX = "Streaming body "
        const val FINAL_MARKER = "stream-final-marker"
        const val THINKING_CHUNKS = 5
        const val CHUNK_INTERVAL_MILLIS = 32L
    }
}
