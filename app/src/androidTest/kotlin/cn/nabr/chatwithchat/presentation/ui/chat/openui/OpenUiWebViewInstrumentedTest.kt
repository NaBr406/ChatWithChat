package cn.nabr.chatwithchat.presentation.ui.chat.openui

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebMessageCompat
import cn.nabr.chatwithchat.data.model.ThemeMode
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiEnvelope
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import cn.nabr.chatwithchat.presentation.ui.chat.OpenUiHostBinding
import cn.nabr.chatwithchat.presentation.ui.chat.OpponentChatBubble
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged React runtime in Android's actual system WebView. */
@RunWith(AndroidJUnit4::class)
class OpenUiWebViewInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var webView: OpenUiWebView
    private val heights = CopyOnWriteArrayList<Double>()
    private val actions = CopyOnWriteArrayList<Pair<String, String>>()

    @Test
    fun conversationLazyColumn_darkFontScale_preservesNarrativeAndScrollsToTail() {
        val before = "Before the panel: adjust the name and amount locally."
        val after = "After the panel: this explanation remains ordinary Markdown."
        val source = mutableStateOf("$before\n\n```openui\n$INPUT_PROGRAM\n```\n\n$after")
        val notices = AtomicInteger()
        val rootView = AtomicReference<View>()
        val hasScrolled = AtomicReference<() -> Boolean>()
        composeRule.setContent {
            ChatWithChatTheme(themeMode = ThemeMode.DARK) {
                val density = LocalDensity.current
                val localView = LocalView.current
                val listState = rememberLazyListState()
                SideEffect {
                    rootView.set(localView.rootView)
                    hasScrolled.set { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
                }
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("openui-conversation"),
                            state = listState,
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            item { Text("Show an interactive form with an explanation.") }
                            item(key = "answer") {
                                OpponentChatBubble(
                                    canRetry = false,
                                    isLoading = false,
                                    text = source.value,
                                    contentIdentity = "conversation:one",
                                    canShowActions = false,
                                    openUiBinding = OpenUiHostBinding("conversation:one", "revision:one", true),
                                    onVisibleContentChanged = { notices.incrementAndGet() }
                                )
                            }
                            item { Text("Conversation tail is reachable") }
                        }
                    }
                }
            }
        }
        composeRule.waitUntil(20000) {
            var ready = false
            onMain {
                findWebView(rootView.get())?.let {
                    webView = it
                    ready = it.isReady && it.pageLoads == 1
                }
            }
            ready
        }
        awaitJs("document.querySelector('input.iui-input') !== null")
        composeRule.onNodeWithText(before).assertIsDisplayed()
        composeRule.onNodeWithText(after).assertIsDisplayed()
        assertEquals("\"dark\"", js("document.documentElement.dataset.theme"))
        onMain { assertEquals(130, webView.settings.textZoom) }
        setInput("preserved in conversation")
        captureScreenshot("openui-dark-font130-narrative.png")
        val initialNotices = notices.get()
        composeRule.runOnIdle {
            source.value += "\n\n${"Additional streamed explanation preserves the form state.\n\n".repeat(40)}"
        }
        composeRule.waitUntil(10000) { notices.get() > initialNotices }
        assertEquals("\"preserved in conversation\"", js("document.querySelector('input.iui-input').value"))
        assertEquals(1, webView.pageLoads)
        composeRule.onNodeWithTag("openui-conversation").performTouchInput { swipeUp() }
        composeRule.runOnIdle { assertTrue("The conversation list must own vertical scrolling", hasScrolled.get().invoke()) }
        composeRule.onNodeWithTag("openui-conversation").performScrollToNode(hasText("Conversation tail is reachable"))
        composeRule.onNodeWithText("Conversation tail is reachable").assertIsDisplayed()
        captureScreenshot("openui-dark-font130-conversation-tail.png")
    }

    @Test
    fun composeAdapter_reportsProgramAndMixedMarkdownHeightChanges() {
        val source = mutableStateOf("```openui\nroot = Col([Text(\"Adapter short\")])\n```")
        val notices = AtomicInteger()
        val renderedHeight = AtomicInteger()
        composeRule.setContent {
            ChatWithChatTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OpenUiChatContent(
                        content = OpenUiEnvelope.parse(source.value),
                        contentIdentity = "adapter:one",
                        isStreaming = false,
                        binding = OpenUiHostBinding("adapter:one", "revision:one", true),
                        modifier = Modifier.onSizeChanged { renderedHeight.set(it.height) },
                        onVisibleContentChanged = { notices.incrementAndGet() }
                    )
                }
            }
        }
        composeRule.waitUntil(20000) { notices.get() > 1 }
        val initial = notices.get()
        val initialHeight = renderedHeight.get()
        composeRule.runOnIdle {
            source.value = "```openui\nroot = Col([Text(\"${"Adapter longer line. ".repeat(100)}\")])\n```"
        }
        composeRule.waitUntil(10000) { notices.get() > initial && renderedHeight.get() > initialHeight }
        val programNotices = notices.get()
        val programHeight = renderedHeight.get()
        composeRule.runOnIdle {
            source.value += "\n\n${"Mixed Markdown paragraph.\n\n".repeat(40)}"
        }
        composeRule.waitUntil(10000) { notices.get() > programNotices && renderedHeight.get() > programHeight }
    }

    @Test
    fun streamingAppendCompletionRollbackReplacementAndIdentity_useOnePage() {
        start(INPUT_PROGRAM, streaming = true)
        awaitJs("document.querySelector('input.iui-input') !== null")
        update(INPUT_PROGRAM)
        setInput("kept")
        assertEquals("\"kept\"", js("document.querySelector('input.iui-input').value"))
        val appended = "$INPUT_PROGRAM\nextra = Text(\"appended\")"
        update(appended, streaming = true)
        assertEquals("\"kept\"", js("document.querySelector('input.iui-input').value"))
        update(appended)
        assertEquals("\"kept\"", js("document.querySelector('input.iui-input').value"))
        update(INPUT_PROGRAM)
        awaitJs("document.querySelector('input.iui-input')?.value === 'initial'")
        update(INPUT_PROGRAM.replace("initial", "changed"))
        awaitJs("document.querySelector('input.iui-input')?.value === 'changed'")
        setInput("local")
        update(INPUT_PROGRAM, identity = "answer:second")
        awaitJs("document.querySelector('input.iui-input')?.value === 'initial'")
        assertEquals(1, webView.pageLoads)
    }

    @Test
    fun localInputAndSlider_updateBoundText() {
        start(INPUT_PROGRAM)
        setInput("Android")
        js("(() => { const e=document.querySelector('input[type=range]'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,'42'); e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); return true; })()")
        awaitJs("document.body.textContent.includes('Android') && document.body.textContent.includes('42')")
        assertTrue("Local controls must not emit external actions", actions.isEmpty())
    }

    @Test
    fun longAnswerAndDelayedContent_reportFullHeightAndTail() {
        val longText = ("Long Android WebView line 中文长回答 0123456789. ".repeat(6000)) + "FINALTAIL931"
        start("root = Col([Text(${JSONObject.quote(longText)})])")
        awaitJs("document.getElementById('root').getBoundingClientRect().height > 1000", timeout = 15000)
        val expectedHeight = jsNumber("document.getElementById('root').getBoundingClientRect().height")
        composeRule.waitUntil(10000) { heights.any { it >= expectedHeight } }
        val tailReady = runCatching {
            awaitJs("document.body.textContent.includes('FINALTAIL931')", timeout = 15000)
            true
        }.getOrDefault(false)
        val tailDiagnostics = if (!tailReady) {
            js(
                """JSON.stringify((() => {
                    const root = document.getElementById('root');
                    const bodyText = document.body?.textContent || '';
                    return {
                        readyState: document.readyState,
                        bodyTextLength: bodyText.length,
                        bodyTextTail: bodyText.slice(-300),
                        rootHtmlTail: (root?.innerHTML || '').slice(-300),
                        rootHeight: root?.getBoundingClientRect().height || 0,
                        rootScrollHeight: root?.scrollHeight || 0,
                        rootChildren: root?.childElementCount || 0,
                        hostReady: Boolean(window.OpenUiHost)
                    };
                })())"""
            )
        } else {
            ""
        }
        assertTrue("Long answer tail was not rendered. Diagnostics: $tailDiagnostics", tailReady)
        val before = heights.last()
        js("setTimeout(() => { const image=new Image(); image.width=40; image.height=1500; image.src='data:image/svg+xml,'+encodeURIComponent('<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"1500\"></svg>'); document.getElementById('root').appendChild(image); }, 100)")
        composeRule.waitUntil(10000) { heights.lastOrNull()?.let { it > before + 1000 } == true }
        assertEquals(1, webView.pageLoads)
    }

    @Test
    fun popoverPortal_openState_isIncludedInReportedHeight() {
        start("root = Col([Text(\"Popover fixture\"), Popover(\"Details\", Text(\"Panel content that must remain visible\"))])")
        awaitJs("document.querySelector('.iui-popover-trigger') !== null && document.getElementById('root').scrollHeight > 1")
        composeRule.waitUntil(10000) { heights.lastOrNull()?.let { it > 1.0 } == true }
        val before = heights.last()
        js("document.querySelector('.iui-popover-trigger').click()")
        awaitJs("document.querySelector('.iui-popover') !== null")
        awaitJs("(() => { const root=document.getElementById('root'); const panel=document.querySelector('.iui-popover'); return root.scrollHeight >= panel.offsetTop + panel.offsetHeight; })()")
        composeRule.waitUntil(10000) { heights.lastOrNull()?.let { it > before } == true }
        val contentHeight = js("document.getElementById('root').scrollHeight").trim('"').toDouble()
        assertTrue("Reported WebView height must include the in-flow popover panel", heights.last() >= contentHeight)
    }

    @Test
    fun htmlView_inlineGameRunsInsideIframeAndCannotAccessNativeBridge() {
        start("root = Col([Text(\"Preparing fixture\")])")
        js("window.__sandboxResult=null; window.addEventListener('message',e=>{if(e.data?.test==='android-isolation')window.__sandboxResult=e.data})")
        val html = """
            <button id="play">Start</button><script>
            document.getElementById('play').onclick=function(){
              this.textContent='Ran';
              var escaped=false;try{parent.NativeOpenUi.postMessage('{"type":"action"}');escaped=true}catch(e){}
              parent.postMessage({test:'android-isolation',text:this.textContent,bridge:typeof NativeOpenUi,escaped:escaped},'*');
            };setTimeout(()=>document.getElementById('play').click(),50);
            </script>
        """.trimIndent()
        update("root = Col([HtmlView(${JSONObject.quote(html)})])")
        awaitJs("window.__sandboxResult?.text === 'Ran'", timeout = 15000)
        assertEquals("\"undefined\"", js("window.__sandboxResult.bridge"))
        assertEquals("false", js("window.__sandboxResult.escaped"))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun nativeBridge_rejectsIframeWrongOriginStaleMalformedAndDangerousActions() {
        start("root = Col([Text(\"Bridge fixture\")])")
        val currentRevision = webView.revision
        fun action(revision: Any = currentRevision, kind: String = "ToAssistant", value: String = "draft") = WebMessageCompat(
            JSONObject().put("type", "action").put("identity", "answer:one").put("revision", revision)
                .put("action", kind).put("value", value).toString()
        )
        onMain {
            webView.receive(action(), Uri.parse(OpenUiWebView.ORIGIN), false)
            webView.receive(action(), Uri.parse("https://attacker.example"), true)
            webView.receive(action(currentRevision - 1), Uri.parse(OpenUiWebView.ORIGIN), true)
            webView.receive(action(currentRevision.toString()), Uri.parse(OpenUiWebView.ORIGIN), true)
            webView.receive(action(kind = "OpenUrl", value = "javascript:alert(1)"), Uri.parse(OpenUiWebView.ORIGIN), true)
            webView.receive(action(value = "x".repeat(OpenUiWebView.MAX_ACTION_CHARS + 1)), Uri.parse(OpenUiWebView.ORIGIN), true)
        }
        assertTrue(actions.isEmpty())
        onMain { webView.receive(action(), Uri.parse(OpenUiWebView.ORIGIN), true) }
        assertEquals(listOf("ToAssistant" to "draft"), actions.toList())
        update("root = Col([Text(\"New revision\")])", streaming = true)
        onMain { webView.receive(action(webView.revision), Uri.parse(OpenUiWebView.ORIGIN), true) }
        assertEquals(1, actions.size)
    }

    private fun start(source: String, streaming: Boolean = false) {
        composeRule.setContent {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(500.dp),
                factory = { context ->
                    OpenUiWebView(context).also {
                        webView = it
                        it.onHeight = heights::add
                        it.onAction = { action, value -> actions += action to value }
                        it.update(OpenUiWebView.Content("answer:one", source, streaming, true, false), 1f)
                    }
                },
                onRelease = { it.release() }
            )
        }
        composeRule.waitUntil(20000) { webView.isReady && webView.pageLoads == 1 && heights.isNotEmpty() }
    }

    private fun update(source: String, streaming: Boolean = false, identity: String = "answer:one") {
        onMain { webView.update(OpenUiWebView.Content(identity, source, streaming, true, false), 1f) }
        // evaluateJavascript calls are queued after the update, including flushSync's DOM commit.
        js("true")
    }

    private fun setInput(value: String) {
        js("(() => { const e=document.querySelector('input.iui-input'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,${JSONObject.quote(value)}); e.dispatchEvent(new Event('input',{bubbles:true})); return true; })()")
        awaitJs("document.querySelector('input.iui-input')?.value === ${JSONObject.quote(value)}")
    }

    private fun awaitJs(expression: String, timeout: Long = 10000) {
        composeRule.waitUntil(timeout) { js(expression) == "true" }
    }

    private fun js(expression: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        onMain {
            webView.evaluateJavascript(expression) {
                result.set(it)
                done.countDown()
            }
        }
        assertTrue("WebView evaluation timed out: ${expression.take(120)}", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun jsNumber(expression: String): Double = js(expression).trim('"').toDouble()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun findWebView(view: View?): OpenUiWebView? {
        if (view is OpenUiWebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun captureScreenshot(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.filesDir, "openui-evidence").apply { mkdirs() }
            File(directory, name).outputStream().use { output ->
                assertTrue("The OpenUI screenshot must be written", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val INPUT_PROGRAM = """
            root = Col([Input(${'$'}name, "Name"), Slider(${'$'}n, 0, 100, 1, "Amount"), Text("Value: " + ${'$'}name + " / " + ${'$'}n)])
            ${'$'}name = "initial"
            ${'$'}n = 5
        """.trimIndent()
    }
}
