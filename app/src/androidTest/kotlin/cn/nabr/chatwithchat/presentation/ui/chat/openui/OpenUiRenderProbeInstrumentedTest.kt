package cn.nabr.chatwithchat.presentation.ui.chat.openui

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.context.stripInternalStickerMarkers
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.model.ThemeMode
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiEnvelope
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiPart
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme
import cn.nabr.chatwithchat.presentation.ui.chat.ChatContent
import cn.nabr.chatwithchat.presentation.ui.chat.ChatGlassIconButton
import cn.nabr.chatwithchat.presentation.ui.chat.ChatGlassPolicy
import cn.nabr.chatwithchat.presentation.ui.chat.ChatGlassScope
import cn.nabr.chatwithchat.presentation.ui.chat.ChatViewModel
import cn.nabr.chatwithchat.presentation.ui.chat.ModelSelectionOption
import cn.nabr.chatwithchat.presentation.ui.chat.OpenUiHostBinding
import cn.nabr.chatwithchat.presentation.ui.chat.OpponentChatBubble
import cn.nabr.chatwithchat.presentation.ui.chat.StickerAssetResolver
import cn.nabr.chatwithchat.presentation.ui.chat.composerGlass
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated A/B probe for real assistant content, the production bubble, and packaged WebView. */
@RunWith(AndroidJUnit4::class)
class OpenUiRenderProbeInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher())

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val rootView = AtomicReference<View>()
    private val listState = AtomicReference<LazyListState>()
    private val scope = AtomicReference<CoroutineScope>()
    private val heightChanges = AtomicInteger()
    private lateinit var evidence: File
    private lateinit var mode: String
    private var productionShell = false
    private val autoGlassEnabled = AtomicReference(true)
    private val nativeContentField = OpenUiWebView::class.java.getDeclaredField("content").apply { isAccessible = true }

    @Test
    fun replayFixtureOrLiveContentThroughProductionBubble() {
        val arguments = InstrumentationRegistry.getArguments()
        mode = arguments.getString("renderProbeMode", "auto")
        require(mode in setOf("auto", "plain", "backdrop")) { "renderProbeMode must be auto, plain or backdrop" }
        val shell = arguments.getString("renderProbeShell", "simplified")
        productionShell = shell == "production"
        require(shell in setOf("simplified", "production")) {
            "renderProbeShell must be simplified or production"
        }
        require(!productionShell || mode == "auto") {
            "production shell uses the real automatic backdrop policy; renderProbeMode must be auto"
        }
        val expectedCases = arguments.getString("renderProbeCases", "1").toInt().also { require(it in 1..100) }
        val settleMs = arguments.getString("renderProbeSettleMs", "150").toLong().also { require(it in 0..5000) }
        val filesRoot = instrumentation.targetContext.filesDir.canonicalFile
        val outputDirectory = File(filesRoot, "openui-render-probe").apply { mkdirs() }
        evidence = File(outputDirectory, "$mode-${System.currentTimeMillis()}.jsonl")
        val fixturePath = arguments.getString("fixturePath")
        val liveSource = File(outputDirectory, "live.jsonl")
        val liveMode = fixturePath.isNullOrBlank()
        val cases = if (liveMode) emptyList() else loadCases(filesRoot, fixturePath)
        if (!liveMode) {
            require(cases.size == expectedCases) {
                "fixture count ${cases.size} does not match renderProbeCases=$expectedCases"
            }
        }
        val liveOffsetAtStart = if (liveMode && liveSource.isFile) liveSource.length() else 0L

        record(
            "start",
            extra = JSONObject()
                .put("package", instrumentation.targetContext.packageName)
                .put("webView", WebView.getCurrentWebViewPackage()?.versionName)
                .put("device", Build.FINGERPRINT)
                .put("source", if (liveMode) "live:${liveSource.name}" else fixturePath)
                .put("cases", expectedCases)
                .put("settleMs", settleMs)
                .put("initialSourceOffset", liveOffsetAtStart)
                .put("shell", shell)
        )

        val text = mutableStateOf("")
        val thoughts = mutableStateOf("")
        val identity = mutableStateOf("probe:idle")
        val mounted = mutableStateOf(false)
        val streaming = mutableStateOf(false)
        val dark = mutableStateOf(false)
        composeRule.setContent {
            val localView = LocalView.current
            val state = rememberLazyListState()
            val localScope = rememberCoroutineScope()
            val backdrop = rememberLayerBackdrop()
            val glassPolicy = remember { ChatGlassPolicy() }
            val fakeMessage = MessageV2(content = text.value, thoughts = thoughts.value, platformType = "render-probe")
            val groupedMessages = ChatViewModel.GroupedMessages(
                userMessages = listOf(MessageV2(id = 1, content = "Probe question", platformType = "render-probe", createdAt = 0)),
                assistantMessages = listOf(listOf(fakeMessage))
            )
            val glassEnabled = !productionShell || glassPolicy.isEnabled(groupedMessages.assistantMessages, listOf(0))
            SideEffect {
                rootView.set(localView.rootView)
                listState.set(state)
                scope.set(localScope)
                autoGlassEnabled.set(glassEnabled)
            }
            ChatWithChatTheme(themeMode = if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT) {
                ChatGlassScope(backdrop, enabled = glassEnabled) {
                    if (productionShell && mounted.value) {
                        ChatContent(
                            title = "Render probe",
                            currentModelLabel = "Probe model",
                            currentModelOptions = listOf(ModelSelectionOption("render-probe", "Probe model", "probe")),
                            currentReasoningMode = ReasoningMode.OFF,
                            isMenuItemEnabled = false,
                            isContextCompacted = false,
                            groupedMessages = groupedMessages,
                            indexStates = listOf(0),
                            loadingStates = listOf(if (streaming.value) ChatViewModel.LoadingState.Loading else ChatViewModel.LoadingState.Idle),
                            toolProgressStates = emptyMap(),
                            enabledPlatformLookup = emptyMap(),
                            canUseChat = false,
                            isIdle = !streaming.value,
                            canSubmitOrEdit = false,
                            isAttachmentImportInProgress = false,
                            isLoaded = true,
                            inputState = remember { TextFieldState() },
                            selectedAttachments = emptyList(),
                            selectedTextSkills = emptyList(),
                            availableTextSkills = emptyList(),
                            sessionTextSkills = emptyList(),
                            stickerAssetResolver = object : StickerAssetResolver {
                                override suspend fun openStickerAsset(assetKey: String) = null
                            },
                            onBackAction = {}, onChatTitleItemClick = {}, onContextRangeItemClick = {},
                            onModelOptionSelected = {}, onReasoningModeSelected = {}, onExportChatItemClick = {},
                            onEditQuestion = {}, onEditAssistant = { _, _ -> }, onCopyText = {}, onPlatformClick = { _, _ -> },
                            onSelectText = {}, onRetry = { _, _ -> }, onShowPreviousRevision = { _, _ -> },
                            onShowNextRevision = { _, _ -> }, bindRichInteraction = { _, _ -> null },
                            onRichInteractionEvent = { _, _ -> },
                            bindOpenUi = { turn, platform -> OpenUiHostBinding("probe:$turn:$platform", "probe:$turn:$platform", true) },
                            onOpenUiAction = {}, onFilesSelected = { _, _ -> }, onFileRemoved = {},
                            onTextSkillsSelected = { _, _ -> }, onTextSkillRemoved = {},
                            onSessionTextSkillEnabled = { _, _ -> }, onSessionTextSkillRemoved = {},
                            onStopButtonClick = {}, onSendButtonClick = {},
                            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                            navigationIconContentDescription = "Back"
                        )
                    } else if (!productionShell) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().then(
                                        if (mode == "backdrop") Modifier.layerBackdrop(backdrop) else Modifier
                                    ),
                                    state = state,
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 88.dp)
                                ) {
                                    item(key = "probe-heading") {
                                        Text("Assistant", Modifier.padding(vertical = 12.dp))
                                    }
                                    item(key = "answer") {
                                        if (mounted.value) {
                                            OpponentChatBubble(
                                                modifier = Modifier.fillMaxWidth().onSizeChanged { heightChanges.incrementAndGet() },
                                                canRetry = false,
                                                isLoading = streaming.value,
                                                showPendingIndicator = false,
                                                text = text.value,
                                                thoughts = thoughts.value,
                                                contentIdentity = identity.value,
                                                canShowActions = false,
                                                renderRichContent = false,
                                                openUiBinding = OpenUiHostBinding(identity.value, identity.value, true)
                                            )
                                        }
                                    }
                                    item(key = "probe-tail") {
                                        Text("Probe tail", Modifier.padding(vertical = 20.dp))
                                    }
                                }
                                Row(
                                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(56.dp)
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (mode == "backdrop") {
                                        ChatGlassIconButton(onClick = {}) {
                                            Icon(Icons.Rounded.Menu, contentDescription = "Menu")
                                        }
                                    } else {
                                        IconButton(onClick = {}) { Icon(Icons.Rounded.Menu, contentDescription = "Menu") }
                                    }
                                    Text("Render probe", style = MaterialTheme.typography.titleMedium)
                                    if (mode == "backdrop") {
                                        ChatGlassIconButton(onClick = {}) {
                                            Icon(Icons.Rounded.MoreHoriz, contentDescription = "More")
                                        }
                                    } else {
                                        IconButton(onClick = {}) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "More") }
                                    }
                                }
                                Box(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(76.dp)
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                        .composerGlass(if (mode == "backdrop") backdrop else null, RoundedCornerShape(24.dp))
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        try {
            var completedCases = 0
            if (liveMode) {
                tailLiveSource(liveSource, liveOffsetAtStart, expectedCases, settleMs, text, thoughts, identity, mounted, streaming, dark)
                completedCases = expectedCases
            } else {
                cases.forEachIndexed { caseIndex, fixture ->
                    replayCase(caseIndex, fixture.id, fixture.source, fixture.content, fixture.thoughts, settleMs, text, thoughts, identity, mounted, streaming, dark)
                    completedCases++
                }
            }
            assertEquals("all requested cases must complete", expectedCases, completedCases)
            if (!productionShell) assertTrue("The probe must exercise repeated native size changes", heightChanges.get() > 0)
            record("pass", extra = JSONObject().put("completedCases", completedCases).put("heightChanges", heightChanges.get()))
        } catch (failure: Throwable) {
            record("failure", extra = JSONObject().put("error", failure.stackTraceToString()))
            throw failure
        }
    }

    private fun replayCase(
        caseIndex: Int,
        id: String,
        source: String,
        content: String,
        thoughts: String,
        settleMs: Long,
        text: androidx.compose.runtime.MutableState<String>,
        thoughtState: androidx.compose.runtime.MutableState<String>,
        identity: androidx.compose.runtime.MutableState<String>,
        mounted: androidx.compose.runtime.MutableState<Boolean>,
        streaming: androidx.compose.runtime.MutableState<Boolean>,
        dark: androidx.compose.runtime.MutableState<Boolean>
    ) {
        val digest = sha256(content)
        val lengths = sampledPrefixLengths(content.length)
        composeRule.runOnIdle {
            identity.value = "probe:$id"
            mounted.value = true
            streaming.value = true
            text.value = ""
            thoughtState.value = thoughts
        }
        if (productionShell && thoughts.isNotBlank()) {
            composeRule.waitForIdle()
            record(
                "thoughts-mounted",
                caseIndex,
                webView = currentWebView(),
                extra = JSONObject()
                    .put("id", id).put("thoughtChars", thoughts.length).put("bodyChars", 0)
            )
        }
        record(
            "case-mounted",
            caseIndex,
            webView = currentWebView(),
            extra = JSONObject()
                .put("id", id).put("source", source).put("contentSha256", digest).put("prefixCount", lengths.size)
        )
        var previousUpdate: AcknowledgedUpdate? = null
        lengths.forEachIndexed { step, end ->
            val complete = step == lengths.lastIndex
            applySnapshot(id, content.substring(0, end), complete, step, caseIndex, source, digest, end, text, thoughtState, thoughts, identity, mounted, streaming, dark)
            val update = awaitExpectedUpdate(id, content.substring(0, end), complete, step, previousUpdate)
            if (update != null) previousUpdate = update
            scrollAndSettle(step, settleMs)
            val webView = verifySettledUpdate(id, update)
            record(
                "case-snapshot",
                caseIndex,
                step,
                webView,
                JSONObject()
                    .put("id", id).put("source", source).put("contentSha256", digest).put("prefixChars", end)
                    .put("contentChars", content.length).put("complete", complete)
            )
        }
        finishCase(caseIndex, id, mounted)
    }

    private fun tailLiveSource(
        file: File,
        initialOffset: Long,
        expectedCases: Int,
        settleMs: Long,
        text: androidx.compose.runtime.MutableState<String>,
        thoughtState: androidx.compose.runtime.MutableState<String>,
        identity: androidx.compose.runtime.MutableState<String>,
        mounted: androidx.compose.runtime.MutableState<Boolean>,
        streaming: androidx.compose.runtime.MutableState<Boolean>,
        dark: androidx.compose.runtime.MutableState<Boolean>
    ) {
        val deadline = SystemClock.elapsedRealtime() + LIVE_TIMEOUT_MS
        var offset = initialOffset
        var pending = ByteArray(0)
        var currentId: String? = null
        var previousContent = ""
        var caseIndex = 0
        var step = 0
        var previousUpdate: AcknowledgedUpdate? = null
        while (caseIndex < expectedCases && SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile && file.length() > offset) {
                val available = (file.length() - offset).coerceAtMost(MAX_LIVE_READ_BYTES.toLong()).toInt()
                val chunk = ByteArray(available)
                java.io.RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    val read = input.read(chunk)
                    if (read > 0) {
                        offset += read
                        val combined = pending + chunk.copyOf(read)
                        var lineStart = 0
                        for (index in combined.indices) {
                            if (combined[index] == '\n'.code.toByte()) {
                                val line = String(combined, lineStart, index - lineStart, Charsets.UTF_8).trimEnd('\r')
                                if (line.isNotBlank()) {
                                    val event = JSONObject(line)
                                    val id = event.getString("id")
                                    val content = event.getString("content")
                                    val thoughts = event.optString("thoughts")
                                    val complete = event.getBoolean("complete")
                                    if (currentId == null) {
                                        currentId = id
                                        previousContent = ""
                                        step = 0
                                        previousUpdate = null
                                        composeRule.runOnIdle {
                                            identity.value = "probe:$id"
                                            mounted.value = true
                                            streaming.value = true
                                            text.value = ""
                                            thoughtState.value = thoughts
                                        }
                                        if (productionShell && thoughts.isNotBlank()) {
                                            composeRule.waitForIdle()
                                            record(
                                                "thoughts-mounted",
                                                caseIndex,
                                                extra = JSONObject()
                                                    .put("id", id).put("thoughtChars", thoughts.length).put("bodyChars", 0)
                                            )
                                        }
                                        record(
                                            "case-mounted",
                                            caseIndex,
                                            extra = JSONObject()
                                                .put("id", id).put("source", file.name).put("contentSha256", sha256(content))
                                        )
                                    } else {
                                        require(currentId == id) {
                                            "live JSONL changed id from $currentId to $id before complete=true"
                                        }
                                    }
                                    require(content.startsWith(previousContent)) {
                                        "live content for $id is not a cumulative prefix at snapshot $step"
                                    }
                                    previousContent = content
                                    applySnapshot(id, content, complete, step, caseIndex, file.name, sha256(content), content.length, text, thoughtState, thoughts, identity, mounted, streaming, dark)
                                    val update = awaitExpectedUpdate(id, content, complete, step, previousUpdate)
                                    if (update != null) previousUpdate = update
                                    scrollAndSettle(step, settleMs)
                                    val webView = verifySettledUpdate(id, update)
                                    record(
                                        "case-snapshot",
                                        caseIndex,
                                        step,
                                        webView,
                                        JSONObject()
                                            .put("id", id).put("source", file.name).put("contentSha256", sha256(content))
                                            .put("prefixChars", content.length).put("contentChars", content.length).put("complete", complete)
                                    )
                                    step++
                                    if (complete) {
                                        finishCase(caseIndex, id, mounted)
                                        caseIndex++
                                        currentId = null
                                        if (caseIndex >= expectedCases) break
                                    }
                                }
                                lineStart = index + 1
                            }
                        }
                        pending = combined.copyOfRange(lineStart, combined.size)
                        require(pending.size <= MAX_LIVE_LINE_BYTES) { "live JSONL line exceeds $MAX_LIVE_LINE_BYTES bytes" }
                    }
                }
            } else {
                SystemClock.sleep(LIVE_POLL_MS)
            }
        }
        require(caseIndex == expectedCases) {
            "live source timed out after $caseIndex/$expectedCases complete cases; currentId=$currentId file=${file.absolutePath}"
        }
    }

    private fun applySnapshot(
        id: String,
        content: String,
        complete: Boolean,
        step: Int,
        caseIndex: Int,
        source: String,
        digest: String,
        chars: Int,
        text: androidx.compose.runtime.MutableState<String>,
        thoughtState: androidx.compose.runtime.MutableState<String>,
        thoughts: String,
        identity: androidx.compose.runtime.MutableState<String>,
        mounted: androidx.compose.runtime.MutableState<Boolean>,
        streaming: androidx.compose.runtime.MutableState<Boolean>,
        dark: androidx.compose.runtime.MutableState<Boolean>
    ) {
        require(content.isNotEmpty() || thoughts.isNotBlank()) { "empty content and thoughts snapshot for $id" }
        composeRule.runOnIdle {
            identity.value = "probe:$id"
            mounted.value = true
            text.value = content
            thoughtState.value = thoughts
            streaming.value = !complete
            if (!productionShell) dark.value = step % 2 != 0
        }
        record(
            "before-update",
            caseIndex,
            step,
            currentWebView(),
            JSONObject()
                .put("id", id).put("source", source).put("contentSha256", digest)
                .put("prefixChars", chars).put("thoughtChars", thoughts.length).put("complete", complete)
        )
    }

    private fun awaitExpectedUpdate(
        id: String,
        body: String,
        complete: Boolean,
        step: Int,
        previous: AcknowledgedUpdate?
    ): AcknowledgedUpdate? {
        val displayedBody = if (productionShell) body.stripInternalStickerMarkers() else body
        val parts = OpenUiEnvelope.parse(displayedBody, !complete).parts
        val index = parts.indexOfFirst { it is OpenUiPart.Program }
        if (index < 0) return null
        val program = parts[index] as OpenUiPart.Program
        val bindingIdentity = if (productionShell) "probe:0:0" else "probe:$id"
        val expected = OpenUiWebView.Content(
            identity = "$bindingIdentity:program:$index",
            source = program.source,
            streaming = !complete && !program.isComplete,
            interactive = true,
            dark = !productionShell && step % 2 != 0
        )
        val update = awaitRenderedContent(id, expected)
        if (previous != null && previous.webView === update.webView) {
            // Appending Markdown after a closed fence leaves the native program unchanged.
            val changed = previous.content != expected
            assertTrue(
                "Native revision did not acknowledge changed OpenUI content for $id",
                if (changed) update.revision > previous.revision else update.revision >= previous.revision
            )
        }
        return update
    }

    private fun awaitRenderedContent(id: String, expected: OpenUiWebView.Content): AcknowledgedUpdate {
        val result = AtomicReference<AcknowledgedUpdate>()
        try {
            composeRule.waitUntil(20000) {
                val current = currentMatchingUpdate(expected)
                if (current == null) {
                    false
                } else {
                    // evaluateJavascript is queued after native publish; the host applies updates
                    // with flushSync. Check the program's completion, not the surrounding Markdown.
                    val pageReady = js(
                        current.webView,
                        "document.querySelector('main')?.getAttribute('aria-busy') === '${expected.streaming}'"
                    ) == "true"
                    if (pageReady) {
                        assertTrue(
                            "OpenUI renderer reported an error for $id",
                            js(current.webView, "document.querySelector('.render-error,[role=alert]') === null") == "true"
                        )
                    }
                    val rendered = expected.streaming || js(current.webView, "document.querySelector('main')?.children.length > 0") == "true"
                    val latest = currentMatchingUpdate(expected)
                    if (pageReady && rendered && latest != null && latest.webView === current.webView && latest.revision == current.revision) {
                        result.set(latest)
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (failure: Throwable) {
            recordContentMismatch(id, expected, failure)
            throw failure
        }
        return checkNotNull(result.get())
    }

    private fun recordContentMismatch(id: String, expected: OpenUiWebView.Content, failure: Throwable) {
        val current = currentWebView()
        val actual = AtomicReference<OpenUiWebView.Content>()
        instrumentation.runOnMainSync {
            if (current != null) actual.set(nativeContentField.get(current) as? OpenUiWebView.Content)
        }
        fun details(value: OpenUiWebView.Content?): Any = if (value == null) {
            JSONObject.NULL
        } else {
            JSONObject().put("identity", value.identity)
                .put("sourceSha256", sha256(value.source)).put("sourceChars", value.source.length)
                .put("sourceTail", value.source.takeLast(120))
                .put("streaming", value.streaming).put("interactive", value.interactive).put("dark", value.dark)
        }
        record(
            "content-acknowledgment-failure",
            webView = current,
            extra = JSONObject().put("id", id).put("error", failure.toString())
                .put("expectedContent", details(expected)).put("actualContent", details(actual.get()))
        )
    }

    private fun currentMatchingUpdate(expected: OpenUiWebView.Content): AcknowledgedUpdate? {
        val result = AtomicReference<AcknowledgedUpdate>()
        instrumentation.runOnMainSync {
            findWebView(rootView.get())?.takeIf { it.isAttachedToWindow && it.isReady && it.pageLoads == 1 }?.let { view ->
                val actual = nativeContentField.get(view) as? OpenUiWebView.Content
                if (actual != null && actual == expected) result.set(AcknowledgedUpdate(view, view.revision, actual))
            }
        }
        return result.get()
    }

    private fun verifySettledUpdate(id: String, update: AcknowledgedUpdate?): OpenUiWebView? {
        if (update == null) return null
        if (productionShell) {
            assertFalse("Automatic OpenUI backdrop policy remained enabled for $id", autoGlassEnabled.get())
            scrollOpenUiWebViewIntoView()
        }
        val current = awaitRenderedContent(id, update.content)
        if (productionShell) assertOpenUiWebViewVisible(id, current.webView)
        record(
            "content-acknowledged",
            webView = current.webView,
            extra = JSONObject().put("id", id)
                .put("expectedIdentity", update.content.identity)
                .put("expectedSourceSha256", sha256(update.content.source))
                .put("actualSourceSha256", sha256(current.content.source))
                .put("expectedSourceStreaming", update.content.streaming)
                .put("actualSourceStreaming", current.content.streaming)
        )
        return current.webView
    }

    private fun finishCase(
        caseIndex: Int,
        id: String,
        mounted: androidx.compose.runtime.MutableState<Boolean>
    ) {
        record("before-remove", caseIndex, webView = currentWebView(), extra = JSONObject().put("id", id))
        composeRule.runOnIdle { mounted.value = false }
        composeRule.waitForIdle()
        composeRule.waitUntil(10000) {
            var absent = false
            instrumentation.runOnMainSync { absent = findWebView(rootView.get()) == null }
            absent
        }
        record("removed", caseIndex, extra = JSONObject().put("id", id))
    }

    private fun scrollAndSettle(step: Int, settleMs: Long) {
        if (!productionShell) {
            composeRule.runOnIdle {
                scope.get().launch { listState.get().scrollToItem(if (step % 2 == 0) 2 else 0) }
            }
        }
        composeRule.waitForIdle()
        if (settleMs > 0) SystemClock.sleep(settleMs)
    }

    private fun scrollOpenUiWebViewIntoView() {
        // The bottom anchor can sit after trailing Markdown and hide the WebView above it.
        // Scroll the real list by the current native bounds, reacquiring after every layout.
        repeat(8) {
            composeRule.waitForIdle()
            val current = currentWebView()
            if (current == null) {
                composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(0)
            } else {
                var visible = false
                var delta = 0f
                instrumentation.runOnMainSync {
                    val bounds = Rect()
                    visible = current.isAttachedToWindow && current.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0
                    if (!visible) {
                        val location = IntArray(2)
                        current.getLocationOnScreen(location)
                        val viewport = Rect()
                        rootView.get().getGlobalVisibleRect(viewport)
                        val center = viewport.exactCenterY()
                        val bottom = location[1] + current.height
                        delta = if (bottom <= viewport.top) bottom - center else location[1] - center
                    }
                }
                if (visible) return
                composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, delta)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertOpenUiWebViewVisible(id: String, webView: OpenUiWebView) {
        val visible = AtomicInteger()
        instrumentation.runOnMainSync {
            val bounds = Rect()
            if (webView.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0) visible.set(1)
        }
        record("webview-visibility", webView = webView, extra = JSONObject().put("id", id).put("visible", visible.get() != 0))
        assertTrue("OpenUI WebView never entered the visible screen area for $id", visible.get() != 0)
    }

    private fun currentWebView(): OpenUiWebView? {
        val result = AtomicReference<OpenUiWebView>()
        instrumentation.runOnMainSync { result.set(findWebView(rootView.get())) }
        return result.get()
    }

    private fun js(webView: OpenUiWebView, expression: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(expression) {
                result.set(it)
                done.countDown()
            }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "Probe JS timed out" }
        return result.get()
    }

    private fun record(
        event: String,
        cycle: Int = -1,
        step: Int = -1,
        webView: OpenUiWebView? = null,
        extra: JSONObject = JSONObject()
    ) {
        val row = extra.put("timeMs", System.currentTimeMillis()).put("event", event)
            .put("mode", mode).put("case", cycle).put("step", step)
            .put("autoGlassEnabled", autoGlassEnabled.get())
        val nativeWebViews = JSONArray()
        val visibleWebViewCount = AtomicInteger()
        val openUiWebViewCount = AtomicInteger()
        instrumentation.runOnMainSync {
            collectWebViews(rootView.get()).forEach { nativeView ->
                val screenBounds = Rect()
                val hasVisibleBounds = nativeView.getGlobalVisibleRect(screenBounds)
                val location = IntArray(2)
                nativeView.getLocationOnScreen(location)
                val visible = nativeView.isShown && hasVisibleBounds && nativeView.alpha > 0f
                if (visible) visibleWebViewCount.incrementAndGet()
                if (nativeView is OpenUiWebView) openUiWebViewCount.incrementAndGet()
                nativeWebViews.put(
                    JSONObject()
                        .put("class", nativeView.javaClass.name)
                        .put("url", nativeView.url)
                        .put("visible", visible)
                        .put("attached", nativeView.isAttachedToWindow)
                        .put("leftPx", location[0]).put("topPx", location[1])
                        .put("rightPx", location[0] + nativeView.width).put("bottomPx", location[1] + nativeView.height)
                        .put("visibleLeftPx", screenBounds.left).put("visibleTopPx", screenBounds.top)
                        .put("visibleRightPx", screenBounds.right).put("visibleBottomPx", screenBounds.bottom)
                        .put("widthPx", nativeView.width).put("heightPx", nativeView.height)
                )
            }
        }
        row.put("nativeWebViewCount", nativeWebViews.length())
            .put("openUiWebViewCount", openUiWebViewCount.get())
            .put("otherWebViewCount", nativeWebViews.length() - openUiWebViewCount.get())
            .put("visibleNativeWebViewCount", visibleWebViewCount.get())
            .put("backgroundNativeWebViewCount", nativeWebViews.length() - visibleWebViewCount.get())
            .put("nativeWebViews", nativeWebViews)
        if (webView != null) {
            instrumentation.runOnMainSync {
                val screenBounds = Rect()
                webView.getGlobalVisibleRect(screenBounds)
                val location = IntArray(2)
                webView.getLocationOnScreen(location)
                row.put("instance", System.identityHashCode(webView)).put("widthPx", webView.width)
                    .put("heightPx", webView.height).put("attached", webView.isAttachedToWindow)
                    .put("screenLeftPx", location[0]).put("screenTopPx", location[1])
                    .put("screenRightPx", location[0] + webView.width).put("screenBottomPx", location[1] + webView.height)
                    .put("visibleLeftPx", screenBounds.left).put("visibleTopPx", screenBounds.top)
                    .put("visibleRightPx", screenBounds.right).put("visibleBottomPx", screenBounds.bottom)
                    .put("hasParent", webView.parent != null).put("hardwareAccelerated", webView.isHardwareAccelerated)
                    .put("revision", webView.revision).put("pageLoads", webView.pageLoads)
                    .put("ready", webView.isReady)
            }
        }
        evidence.appendText("$row\n")
        Log.i("OpenUiRenderProbe", row.toString())
    }

    private fun findWebView(view: View?): OpenUiWebView? {
        if (view is OpenUiWebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun collectWebViews(view: View?): List<WebView> {
        if (view == null) return emptyList()
        if (view is WebView) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return buildList {
            for (index in 0 until view.childCount) addAll(collectWebViews(view.getChildAt(index)))
        }
    }

    private fun loadCases(filesRoot: File, relativePath: String): List<ProbeFixture> {
        val input = resolveInside(filesRoot, filesRoot, relativePath)
        if (input.isDirectory) {
            val manifest = File(input, "cases.json")
            if (manifest.isFile) return loadManifest(filesRoot, manifest)
            return input.listFiles { file -> file.isFile && file.extension.equals("json", true) }
                .orEmpty().sortedBy { it.name }.map { parseFixture(it) }
        }
        val json = JSONObject(input.readText())
        val manifestCases = json.optJSONArray("cases")
        if (manifestCases != null) return loadManifest(filesRoot, input)
        return listOf(parseFixture(input, json))
    }

    private fun loadManifest(filesRoot: File, manifest: File): List<ProbeFixture> {
        val cases = JSONObject(manifest.readText()).getJSONArray("cases")
        require(cases.length() > 0) { "fixture manifest has no cases: ${manifest.path}" }
        return buildList {
            for (index in 0 until cases.length()) {
                val item = cases.getJSONObject(index)
                val fixture = resolveInside(filesRoot, checkNotNull(manifest.parentFile), item.getString("fixtureFile"))
                val parsed = parseFixture(fixture)
                val expectedId = item.optString("id")
                require(expectedId.isBlank() || expectedId == parsed.id) { "fixture id mismatch in ${fixture.path}" }
                add(parsed)
            }
        }
    }

    private fun parseFixture(file: File, parsed: JSONObject = JSONObject(file.readText())): ProbeFixture {
        val content = parsed.optString("content")
        require(content.isNotBlank()) { "fixture has no content: ${file.path}" }
        return ProbeFixture(parsed.optString("id", file.nameWithoutExtension), file.relativeTo(instrumentation.targetContext.filesDir.canonicalFile).path, content, parsed.optString("thoughts"))
    }

    private fun resolveInside(root: File, base: File, path: String): File {
        val resolved = File(base, path).canonicalFile
        require(resolved.toPath().startsWith(root.toPath())) { "fixture path escapes targetContext.filesDir: $path" }
        require(resolved.exists()) { "fixture path does not exist: $path" }
        return resolved
    }

    private fun sampledPrefixLengths(length: Int): List<Int> {
        require(length > 0) { "fixture content is empty" }
        val sampleCount = minOf(8, length)
        return (1..sampleCount).map { index -> ((length.toLong() * index) / sampleCount).toInt() }.distinct()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private data class ProbeFixture(val id: String, val source: String, val content: String, val thoughts: String = "")

    private data class AcknowledgedUpdate(val webView: OpenUiWebView, val revision: Long, val content: OpenUiWebView.Content)

    companion object {
        private const val LIVE_TIMEOUT_MS = 20 * 60 * 1000L
        private const val LIVE_POLL_MS = 150L
        private const val MAX_LIVE_READ_BYTES = 1024 * 1024
        private const val MAX_LIVE_LINE_BYTES = 2 * 1024 * 1024
    }
}
