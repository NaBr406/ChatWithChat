package cn.nabr.chatwithchat.presentation.ui.chat.openui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiContent
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiPart
import cn.nabr.chatwithchat.presentation.common.isSettingsDarkTheme
import cn.nabr.chatwithchat.presentation.ui.chat.ChatMarkdown
import cn.nabr.chatwithchat.presentation.ui.chat.OpenUiHostAction
import cn.nabr.chatwithchat.presentation.ui.chat.OpenUiHostBinding

/** Explicit envelopes coexist with ordinary Markdown; neither legacy Markdown nor XML is guessed. */
@Composable
internal fun OpenUiChatContent(
    content: OpenUiContent,
    contentIdentity: Any,
    isStreaming: Boolean,
    binding: OpenUiHostBinding?,
    modifier: Modifier = Modifier,
    onVisibleContentChanged: () -> Unit = {},
    onAction: (OpenUiHostAction) -> Unit = {}
) {
    val currentOnVisibleContentChanged by rememberUpdatedState(onVisibleContentChanged)
    Column(
        modifier = modifier.fillMaxWidth().onSizeChanged { currentOnVisibleContentChanged() }
    ) {
        content.parts.forEachIndexed { index, part ->
            key("$contentIdentity:$index") {
                when (part) {
                    is OpenUiPart.Markdown -> if (part.text.isNotBlank()) {
                        ChatMarkdown(
                            content = part.text,
                            contentIdentity = "$contentIdentity:markdown:$index",
                            isStreaming = isStreaming
                        )
                    }
                    is OpenUiPart.Program -> OpenUiProgram(
                        source = part.source,
                        identity = "${binding?.identity ?: contentIdentity}:program:$index",
                        isStreaming = isStreaming && !part.isComplete,
                        binding = binding,
                        onAction = onAction
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenUiProgram(
    source: String,
    identity: String,
    isStreaming: Boolean,
    binding: OpenUiHostBinding?,
    onAction: (OpenUiHostAction) -> Unit
) {
    val density = LocalDensity.current
    val dark = isSettingsDarkTheme()
    val owner = LocalLifecycleOwner.current
    val currentBinding by rememberUpdatedState(binding)
    val currentOnAction by rememberUpdatedState(onAction)
    // Seeded from the cache so a WebView recreated on scroll-back opens at its last height instead of
    // 1 dp, which blanked the answer and made the list jump.
    var height by remember(identity) { mutableDoubleStateOf(OpenUiHeightCache.get(identity) ?: 1.0) }
    val viewHolder = remember { arrayOfNulls<OpenUiWebView>(1) }
    var unavailable by remember { mutableStateOf(false) }
    // Compose Constraints have a finite representation. Exceptionally huge documents remain
    // reachable through WebView's own scroll instead of truncating their tail at a fixed cutoff.
    val maxHeightDp = 200000.0 / density.density
    val visibleHeight = height.coerceIn(1.0, maxHeightDp)
    if (unavailable) {
        Text(stringResource(R.string.openui_webview_unavailable))
    } else {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(visibleHeight.dp),
            factory = { context ->
                OpenUiWebView(context).also { created ->
                    viewHolder[0] = created
                    created.setPagePaused(!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    created.onUnavailable = { unavailable = true }
                }
            },
            update = { webView ->
                webView.onHeight = { nextHeight ->
                    if (height != nextHeight) {
                        height = nextHeight
                    }
                    if (nextHeight > 1.0) OpenUiHeightCache.put(identity, nextHeight)
                }
                webView.onAction = { action, value ->
                    currentBinding?.let { current ->
                        if (current.interactive) currentOnAction(OpenUiHostAction(current.identity, current.revision, action, value))
                    }
                }
                webView.isOversized = height > maxHeightDp
                webView.update(
                    OpenUiWebView.Content(identity, source, isStreaming, binding?.interactive == true, dark),
                    density.fontScale
                )
            },
            onRelease = {
                if (viewHolder[0] === it) viewHolder[0] = null
                it.release()
            }
        )
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            viewHolder[0]?.setPagePaused(!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        owner.lifecycle.addObserver(observer)
        viewHolder[0]?.setPagePaused(!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** Last measured CSS height per program identity, shared across WebView instances. Main thread only. */
private object OpenUiHeightCache {
    private val heights = android.util.LruCache<String, Double>(256)

    fun get(identity: String): Double? = heights.get(identity)

    fun put(identity: String, height: Double) {
        heights.put(identity, height)
    }
}
