package cn.nabr.chatwithchat.presentation.ui.chat.openui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.model.ThemeMode
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.richcontent.RichContentPromptBuilder
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiEnvelope
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import cn.nabr.chatwithchat.presentation.ui.chat.OpenUiHostAction
import cn.nabr.chatwithchat.presentation.ui.chat.OpenUiHostBinding
import cn.nabr.chatwithchat.presentation.ui.chat.OpponentChatBubble
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.decodeFromString
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Replays captured SSE through the actual OpponentChatBubble and packaged WebView. */
@RunWith(Parameterized::class)
class OpenUiLiveAcceptanceInstrumentedTest(
    private val id: String,
    private val component: String,
    private val fixtureFile: String,
    private val fixtureSha256: String,
    private val expectedPromptHash: String,
    private val expectedRuntimePromptHash: String,
    private val generationPromptFile: String
) {
    // Markdown parses on Default. Queue its state updates for the test clock instead of
    // allowing the default unconfined test dispatcher to resume Android layout on that worker.
    @get:Rule
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher())

    private val actions = CopyOnWriteArrayList<OpenUiHostAction>()
    private val visibleChanges = AtomicInteger()
    private val replayedPrefixLengths = JSONArray()
    private val screenshots = JSONArray()
    private val viewport = AtomicReference<IntArray>()
    private val listState = AtomicReference<LazyListState>()
    private val mainScope = AtomicReference<CoroutineScope>()
    private val scrollEvidence = JSONArray()
    private val programPrefixes = AtomicInteger()
    private var actualPromptSha256: String? = null
    private var promptComparison = "not-verified"
    private var failureStackTrace: String? = null
    private val rootView = AtomicReference<View>()
    private lateinit var webView: OpenUiWebView

    @Test
    fun generatedFixture_replaysCompleteEnvelopeAndTargetControl() {
        val failures = mutableListOf<String>()
        fun verify(condition: Boolean, message: String) {
            if (!condition) failures += message
        }
        var geometry = "{}"
        var decodedChars = 0
        try {
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "openui-live-acceptance")
            val file = fixtureFile(directory, fixtureFile)
            check(sha256(file) == fixtureSha256) { "case fixture SHA-256 mismatch" }
            // JUnit retains only manifest metadata; one case is loaded for this test.
            val fixture = JSONObject(file.readText())
            check(fixture.getString("id") == id && fixture.getString("component") == component) { "case fixture identity mismatch" }
            check(fixture.getString("finishReason") == "stop") { "generation finish reason is not stop" }
            check(fixture.getString("systemPromptSha256") == expectedPromptHash) { "case generation prompt SHA-256 mismatch" }
            val content = fixture.getString("content")
            val program = fixture.getString("program")
            val generationPrompt = fixtureFile(directory, generationPromptFile).readText()
            val envelope = OpenUiEnvelope.parse(content)
            verify(envelope.source != null, "OpenUI fence missing")
            verify(envelope.isComplete, "OpenUI fence incomplete")
            verify(envelope.markdown.isNotBlank() && Regex("[\\u3400-\\u9fff]").containsMatchIn(envelope.markdown), "Chinese semantic explanation missing")
            verify(envelope.source?.trim() == program.trim(), "fixture program differs from parsed source")
            verify(Regex("\\b${Regex.escape(component)}\\s*\\(").containsMatchIn(program), "target component call missing: $component")
            val actualPrompt = RichContentPromptBuilder().build()
            actualPromptSha256 = sha256(actualPrompt)
            val promptDirectory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "openui-live-acceptance").apply { mkdirs() }
            File(promptDirectory, "actual-system-prompt.txt").writeText(actualPrompt)
            verify(expectedRuntimePromptHash == actualPromptSha256, "runtime system prompt SHA-256 mismatch")
            verify(expectedPromptHash == sha256(generationPrompt), "generation system prompt SHA-256 mismatch")
            val marker = "\n\nHost integration rules"
            promptComparison = when {
                generationPrompt == actualPrompt -> "exact"
                actualPrompt.indexOf(marker) >= 0 &&
                    actualPrompt.indexOf(marker) == actualPrompt.lastIndexOf(marker) &&
                    actualPrompt.replaceFirst(marker, "\nHost integration rules") == generationPrompt -> "one-blank-line-before-host-rules"
                else -> "mismatch"
            }
            verify(promptComparison != "mismatch", "generation and runtime system prompts differ beyond the single allowed host-rules separator")

            val sseFile = fixtureFile(directory, fixture.getString("sseFile"))
            check(sha256(sseFile) == fixture.getString("sseSha256")) { "raw SSE SHA-256 mismatch" }
            val replay = decodeSsePrefixes(sseFile)
            check(replay.contentDeltaCount == fixture.getInt("contentDeltaCount")) { "raw SSE content delta count mismatch" }
            val decodedContent = replay.content
            decodedChars = decodedContent.length
            check(decodedContent == content) { "raw SSE decoded content differs from fixture content" }
            // Boundaries come from real nonempty content deltas. Materialize only
            // the current sample, never every accumulated prefix (quadratic memory).
            val sampled = replay.sampledPrefixLengths

            val theme = mutableStateOf(ThemeMode.LIGHT)
            val streamedText = mutableStateOf(decodedContent.substring(0, sampled.first()))
            val isStreaming = mutableStateOf(true)
            composeRule.setContent {
                rootView.set(LocalView.current.rootView)
                val state = rememberLazyListState()
                val scope = rememberCoroutineScope()
                SideEffect {
                    listState.set(state)
                    mainScope.set(scope)
                }
                ChatWithChatTheme(themeMode = theme.value) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                                viewport.set(intArrayOf(it.positionInWindow().y.toInt(), it.size.height))
                            },
                            state = state
                        ) {
                            item(key = id) {
                                OpponentChatBubble(
                                    canRetry = false,
                                    isLoading = isStreaming.value,
                                    text = streamedText.value,
                                    contentIdentity = "live:$id",
                                    canShowActions = false,
                                    openUiBinding = OpenUiHostBinding("live:$id", "fixture:1", true),
                                    onOpenUiAction = actions::add,
                                    onVisibleContentChanged = { visibleChanges.incrementAndGet() }
                                )
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            awaitProgramPrefix(streamedText.value)
            replayedPrefixLengths.put(sampled.first())
            for (length in sampled.drop(1)) {
                val prefix = decodedContent.substring(0, length)
                composeRule.runOnIdle { streamedText.value = prefix }
                composeRule.waitForIdle()
                awaitProgramPrefix(prefix)
                replayedPrefixLengths.put(length)
            }
            composeRule.runOnIdle {
                streamedText.value = decodedContent
                isStreaming.value = false
            }
            composeRule.waitUntil(20000) {
                var ready = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    findWebView(rootView.get())?.let {
                        webView = it
                        ready = it.isReady && it.pageLoads == 1
                    }
                }
                ready
            }
            val selector = targetSelector(component)
            awaitJs("document.querySelector(${JSONObject.quote(selector)}) !== null")
            awaitJs("document.querySelector('main')?.getAttribute('aria-busy') === 'false'")
            awaitAnimationFrames()
            geometry = decodeJsObject(js("(() => { const e=document.querySelector(${JSONObject.quote(selector)}); const r=e?.getBoundingClientRect(); return JSON.stringify({width:r?.width??0,height:r?.height??0,rootHeight:document.querySelector('#root')?.getBoundingClientRect().height??0,viewportWidth:window.innerWidth}); })()"))
            val width = JSONObject(geometry).optDouble("width", Double.NaN)
            val height = JSONObject(geometry).optDouble("height", Double.NaN)
            verify(width.isFinite() && height.isFinite() && width >= 0 && height >= 0, "target geometry is invalid")
            if (component in setOf("Spacer", "Divider", "Line", "Path", "Polyline")) {
                verify(width > 0 || height > 0, "target geometry has no extent")
            } else {
                verify(width > 0 && height > 0, "target geometry has an empty dimension")
            }
            verify(JSONObject(geometry).optDouble("rootHeight", 0.0) > 1.0, "rendered DOM height missing")
            composeRule.waitUntil(10000) { visibleChanges.get() > 0 }
            verify(js("document.querySelectorAll('[role=alert], .render-error').length") == "0", "light theme renderer error alert present")

            scrollWebViewIntoView("light")
            captureScreenshot("light")
            composeRule.runOnIdle { theme.value = ThemeMode.DARK }
            composeRule.waitForIdle()
            awaitJs("document.documentElement.dataset.theme === 'dark'")
            awaitAnimationFrames()
            verify(js("document.querySelectorAll('[role=alert], .render-error').length") == "0", "dark theme renderer error alert present")
            scrollWebViewIntoView("dark")
            captureScreenshot("dark")
        } catch (error: Throwable) {
            failureStackTrace = error.stackTraceToString()
            failures += "${error::class.simpleName}: ${error.message}"
        } finally {
            if (failures.isNotEmpty()) {
                runCatching { captureScreenshot("failure") }.onFailure { failures += "failure screenshot: ${it.message}" }
            }
            writeResult(failures, geometry, decodedChars)
        }
        assertTrue("$id acceptance defects: ${failures.joinToString()}", failures.isEmpty())
    }

    private fun targetSelector(component: String): String = when (component) {
        "Button" -> "button.iui-button"
        "IconButton" -> "button.iui-icon-button"
        "Pressable" -> ".iui-pressable"
        "Input" -> ".iui-field input"
        "TextArea" -> "textarea"
        "Select" -> "select.iui-select"
        "SegmentedControl" -> ".iui-segmented"
        "Slider" -> "input[type=range]"
        "Checkbox" -> "input[type=checkbox]"
        "RadioGroup" -> ".iui-radio-group"
        "DatePicker" -> "input[type=date]"
        "Timer" -> "[role=timer]"
        "Form" -> "form.iui-form"
        "Col" -> ".iui-col"
        "Row" -> ".iui-row"
        "Box" -> ".iui-box"
        "Grid" -> ".iui-grid"
        "Card" -> ".iui-card"
        "Carousel" -> ".iui-carousel"
        "Popover" -> ".iui-popover-trigger"
        "Divider" -> ".iui-divider"
        "Spacer" -> ".iui-spacer"
        "Title" -> ".iui-title"
        "Text" -> ".iui-text"
        "Caption" -> ".iui-caption"
        "Label" -> ".iui-label"
        "Badge" -> ".iui-badge"
        "Link" -> ".iui-link"
        "List" -> ".iui-list"
        "ListItem" -> ".iui-list-body"
        "Markdown" -> ".iui-markdown"
        "Quote" -> ".iui-quote"
        "Code" -> ".iui-codeblock"
        "Math" -> ".iui-math"
        "SearchImage", "Image" -> ".iui-image, .iui-network-status"
        "Icon" -> ".iui-icon"
        "Table" -> ".iui-table"
        "Chart" -> ".iui-chart"
        "Map" -> ".iui-map"
        "MapMarker" -> ".iui-map-marker"
        "YouTube" -> ".iui-video"
        "Flashcard" -> ".iui-flashcard"
        // The generated quiz renders Celebration only after answers change false -> true.
        // Initial Android rendering checks the quiz; browser acceptance tests the burst.
        "Celebration" -> ".iui-radio-group"
        "Svg" -> "svg.iui-svg"
        "Circle" -> "svg.iui-svg circle"
        "Ellipse" -> "svg.iui-svg ellipse"
        "Rect" -> "svg.iui-svg rect"
        "Line" -> "svg.iui-svg line"
        "Path" -> "svg.iui-svg path"
        "Polyline" -> "svg.iui-svg polyline"
        "Polygon" -> "svg.iui-svg polygon"
        "G" -> "svg.iui-svg g"
        "SvgText" -> "svg.iui-svg text"
        "HtmlView" -> ".iui-html-view"
        else -> error("No target selector for $component")
    }

    private fun awaitJs(expression: String, timeout: Long = 15000) {
        composeRule.waitUntil(timeout) { js(expression) == "true" }
    }

    private fun awaitProgramPrefix(prefix: String) {
        if (OpenUiEnvelope.parse(prefix).source.isNullOrBlank()) return
        composeRule.waitUntil(20000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                findWebView(rootView.get())?.let {
                    webView = it
                    ready = it.isReady && it.pageLoads == 1
                }
            }
            ready
        }
        awaitAnimationFrames()
        programPrefixes.incrementAndGet()
    }

    private fun awaitAnimationFrames() {
        js("window.__liveAcceptanceFrameReady=false; requestAnimationFrame(()=>requestAnimationFrame(()=>{window.__liveAcceptanceFrameReady=true}));")
        awaitJs("window.__liveAcceptanceFrameReady === true")
    }

    private fun scrollWebViewIntoView(theme: String) {
        val before = webViewPosition()
        // Both coordinates use the app window. Keep the WebView's first rendered panel
        // inside the LazyColumn viewport even when ordinary Markdown precedes its fence.
        val bounds = checkNotNull(viewport.get()) { "Conversation viewport missing" }
        val delta = before[1] - bounds[0] - 16
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        composeRule.runOnIdle {
            mainScope.get().launch {
                try {
                    listState.get().scrollBy(delta.toFloat())
                } finally {
                    finished.set(true)
                }
            }
        }
        composeRule.waitUntil(10000) { finished.get() }
        composeRule.waitForIdle()
        awaitAnimationFrames()
        val after = webViewPosition()
        check(after[1] < bounds[0] + bounds[1] && after[1] + after[3] > bounds[0]) { "OpenUI WebView remains outside conversation viewport" }
        scrollEvidence.put(
            JSONObject().put("theme", theme)
                .put("before", JSONArray(before.toList()))
                .put("after", JSONArray(after.toList()))
                .put("viewportTopPx", bounds[0]).put("viewportHeightPx", bounds[1])
        )
    }

    private fun webViewPosition(): IntArray {
        val result = IntArray(4)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.getLocationInWindow(result)
            result[2] = webView.width
            result[3] = webView.height
        }
        return result
    }

    private fun js(expression: String): String {
        val value = AtomicReference<String>()
        val lock = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(expression) { result ->
                value.set(result)
                lock.countDown()
            }
        }
        check(lock.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "WebView evaluation timeout" }
        return value.get() ?: "null"
    }

    private fun decodeJsObject(value: String): String {
        val parsed = JSONTokener(value).nextValue()
        return if (parsed is String) parsed else parsed.toString()
    }

    private fun findWebView(view: View?): OpenUiWebView? {
        if (view is OpenUiWebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun captureScreenshot(theme: String) {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        try {
            val name = "$id-$theme-${bitmap.width}px.png"
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "openui-live-acceptance/evidence").apply { mkdirs() }
            File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshots.put(JSONObject().put("file", name).put("widthPx", bitmap.width).put("heightPx", bitmap.height))
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeResult(failures: List<String>, geometry: String, decodedChars: Int) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "openui-live-acceptance/results").apply { mkdirs() }
        File(directory, "$id.json").writeText(
            JSONObject()
                .put("id", id)
                .put("component", component)
                .put("status", if (failures.isEmpty()) "pass" else "fail")
                .put("failures", JSONArray(failures))
                .put("failureStackTrace", failureStackTrace ?: JSONObject.NULL)
                .put("geometry", JSONObject(geometry))
                .put("decodedSseContentChars", decodedChars)
                .put("actualPromptSha256", actualPromptSha256 ?: JSONObject.NULL)
                .put("expectedPromptSha256", expectedPromptHash)
                .put("expectedRuntimePromptSha256", expectedRuntimePromptHash)
                .put("promptComparison", promptComparison)
                .put("replayedPrefixLengths", replayedPrefixLengths)
                .put("visibleContentCallbacks", visibleChanges.get())
                .put("renderedProgramPrefixes", programPrefixes.get())
                .put("behavior", "not-tested")
                .put(
                    "targetRendering",
                    if (failures.isNotEmpty()) {
                        "not-verified"
                    } else if (component == "Celebration") {
                        "conditional-burst-not-tested; quiz-rendered"
                    } else {
                        "target-rendered"
                    }
                )
                .put("actions", actions.size)
                .put("screenshots", screenshots)
                .put("scrollEvidence", scrollEvidence)
                .toString(2)
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fixtureFile(directory: File, relativePath: String): File {
        val file = File(directory, relativePath).canonicalFile
        check(file.path.startsWith(directory.canonicalPath + File.separator)) { "Fixture path escapes acceptance directory" }
        check(file.isFile) { "Live acceptance fixture missing: ${file.absolutePath}" }
        return file
    }

    private data class SseReplay(val content: String, val sampledPrefixLengths: List<Int>, val contentDeltaCount: Int)

    private fun decodeSsePrefixes(sseFile: File): SseReplay {
        check(sseFile.length() > 0) { "raw SSE fixture missing" }
        val boundaries = mutableListOf<Int>()
        val decoded = StringBuilder()
        val dataLines = mutableListOf<String>()
        var finishReason: String? = null
        var sawDone = false
        fun decodeEvent() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n")
            dataLines.clear()
            check(!sawDone) { "SSE event after [DONE]" }
            if (data == "[DONE]") {
                sawDone = true
                return
            }
            val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(data)
            check(chunk.error == null) { "SSE provider error: ${chunk.error?.message}" }
            chunk.choices.orEmpty().filter { it.index == 0 }.forEach { choice ->
                choice.finishReason?.let { finishReason = it }
                choice.delta.content?.takeIf { it.isNotEmpty() }?.let {
                    decoded.append(it)
                    boundaries += decoded.length
                }
            }
        }
        sseFile.bufferedReader().useLines { lines ->
            for (line in lines) {
                when {
                    line.isEmpty() -> decodeEvent()
                    line == "data" -> dataLines += ""
                    line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
                }
            }
        }
        decodeEvent()
        check(sawDone) { "SSE [DONE] terminator missing" }
        check(finishReason == "stop") { "SSE finish reason is $finishReason" }
        check(boundaries.isNotEmpty()) { "SSE has no content deltas" }
        val partialCount = boundaries.indexOfFirst { it == decoded.length }
        check(partialCount > 0) { "raw SSE contains no incremental content prefixes" }
        val sampleCount = minOf(8, partialCount)
        val sampledLengths = (1..sampleCount).map { boundaries[it * partialCount / sampleCount - 1] }
        return SseReplay(decoded.toString(), sampledLengths, boundaries.size)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun fixtures(): Collection<Array<Any?>> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.filesDir, "openui-live-acceptance/cases.json")
            check(file.isFile) { "Live acceptance fixtures missing: ${file.absolutePath}" }
            val envelope = JSONObject(file.readText())
            check(envelope.getInt("schemaVersion") == 2) { "Live acceptance manifest must use per-case schema 2" }
            val hash = envelope.getString("systemPromptSha256")
            val runtimeHash = envelope.getString("runtimeSystemPromptSha256")
            val generationPromptFile = envelope.getString("generationSystemPromptFile")
            val cases = envelope.getJSONArray("cases")
            check(cases.length() > 0) { "Live acceptance fixtures contain no cases" }
            return buildList {
                for (index in 0 until cases.length()) {
                    val item = cases.getJSONObject(index)
                    add(arrayOf(item.getString("id"), item.getString("component"), item.getString("fixtureFile"), item.getString("fixtureSha256"), hash, runtimeHash, generationPromptFile))
                }
            }
        }
    }
}
