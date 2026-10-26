package cn.nabr.chatwithchat.presentation.ui.chat.openui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlin.math.abs
import org.json.JSONObject

/** One trusted local page; model programs enter as JSON data, never as executable scripts. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class OpenUiWebView(context: Context) : WebView(context) {
    data class Content(
        val identity: String,
        val source: String,
        val streaming: Boolean,
        val interactive: Boolean,
        val dark: Boolean
    )

    var onHeight: (Double) -> Unit = {}
    var onAction: (String, String) -> Unit = { _, _ -> }
    var onUnavailable: () -> Unit = {}
    var isOversized: Boolean = false
    var pageLoads: Int = 0
        private set
    var revision: Long = 0
        private set
    var isReady: Boolean = false
        private set
    private var content: Content? = null
    private var destroyed = false
    private var paused = false
    private var selecting = false
    private var touchX = 0f
    private var touchY = 0f
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == HOST) {
                    return loader.shouldInterceptRequest(uri) ?: blockedResponse()
                }
                // Remote HTTPS media and map resources are explicitly network dependent.
                return if (uri.scheme == "https" || uri.scheme == "data" || uri.scheme == "blob") null else blockedResponse()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && request.url.toString() != PAGE_URL) {
                    if (request.hasGesture() && isPublicWebUrl(request.url.toString())) dispatchAction("OpenUrl", request.url.toString())
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == PAGE_URL) pageLoads++
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onUnavailable()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onUnavailable()
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, BRIDGE, setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
                receive(message, sourceOrigin, isMainFrame)
            }
            loadUrl(PAGE_URL)
        } else {
            // No insecure JavascriptInterface fallback on old system WebViews.
            post { onUnavailable() }
        }
    }

    fun update(value: Content, fontScale: Float) {
        if (destroyed) return
        if (value.source.length > MAX_SOURCE_CHARS) {
            content = null
            revision++
            onUnavailable()
            return
        }
        settings.textZoom = (fontScale * 100).toInt().coerceIn(50, 300)
        if (value == content) return
        if (content?.identity != value.identity) onHeight(1.0)
        content = value
        revision++
        if (isReady) publish()
    }

    private fun publish() {
        val value = content ?: return
        val payload = JSONObject()
            .put("identity", value.identity)
            .put("revision", revision)
            .put("source", value.source)
            .put("streaming", value.streaming)
            .put("interactive", value.interactive && !value.streaming)
            .put("theme", if (value.dark) "dark" else "light")
        evaluateJavascript("window.OpenUiHost && window.OpenUiHost.update($payload)", null)
    }

    internal fun receive(message: WebMessageCompat, origin: Uri, mainFrame: Boolean) {
        if (destroyed ||
            !mainFrame ||
            origin.scheme != "https" ||
            origin.host != HOST ||
            origin.port !in listOf(-1, 443) ||
            origin.userInfo != null ||
            !origin.path.isNullOrEmpty() ||
            origin.query != null ||
            origin.fragment != null
        ) {
            return
        }
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val raw = message.data ?: return
        if (raw.length > MAX_MESSAGE_CHARS) return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.optString("type") == "ready") {
            if (!isReady) {
                isReady = true
                publish()
                setPagePaused(paused)
            }
            return
        }
        val current = content ?: return
        if (json.opt("identity") !is String ||
            json.opt("revision") !is Number ||
            json.optString("identity") != current.identity ||
            json.optDouble("revision") != revision.toDouble()
        ) {
            return
        }
        when (json.optString("type")) {
            "height" -> {
                if (json.opt("height") !is Number) return
                val height = json.optDouble("height", Double.NaN)
                if (height.isFinite() && height >= 0 && height <= MAX_CSS_HEIGHT) onHeight(height.coerceAtLeast(1.0))
            }
            "selection" -> selecting = json.optBoolean("active", false)
            "action" -> {
                if (json.opt("action") !is String || json.opt("value") !is String) return
                dispatchAction(json.getString("action"), json.getString("value"))
            }
        }
    }

    private fun dispatchAction(action: String, value: String) {
        val current = content ?: return
        if (paused || !current.interactive || current.streaming || value.length > MAX_ACTION_CHARS || value.isBlank()) return
        if (action !in setOf("ToAssistant", "OpenUrl", "CopyToClipboard")) return
        if (action == "OpenUrl" && !isPublicWebUrl(value)) return
        onAction(action, value)
    }

    fun setPagePaused(value: Boolean) {
        paused = value
        if (destroyed) return
        if (value) onPause() else onResume()
        if (isReady) evaluateJavascript("window.OpenUiHost && window.OpenUiHost.setPaused?.($value)", null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                parent?.requestDisallowInterceptTouchEvent(selecting)
            }
            MotionEvent.ACTION_MOVE -> {
                val horizontal = abs(event.x - touchX) > abs(event.y - touchY)
                // The Compose list owns vertical scrolling except beyond its representable height.
                parent?.requestDisallowInterceptTouchEvent(selecting || horizontal || isOversized)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!selecting) parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = super.startActionMode(
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = callback.onCreateActionMode(mode, menu).also {
                selecting = it
                parent?.requestDisallowInterceptTouchEvent(it)
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = callback.onActionItemClicked(mode, item)
            override fun onDestroyActionMode(mode: ActionMode) {
                selecting = false
                parent?.requestDisallowInterceptTouchEvent(false)
                callback.onDestroyActionMode(mode)
            }
        },
        type
    )

    fun release() {
        if (destroyed) return
        destroyed = true
        onHeight = {}
        onAction = { _, _ -> }
        onUnavailable = {}
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(this, BRIDGE)
        stopLoading()
        removeAllViews()
        destroy()
    }

    private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val PAGE_URL = "$ORIGIN/assets/openui/index.html"
        const val BRIDGE = "NativeOpenUi"
        const val MAX_MESSAGE_CHARS = 65536
        const val MAX_ACTION_CHARS = 32768
        const val MAX_SOURCE_CHARS = 1048576
        const val MAX_CSS_HEIGHT = 10000000.0

        fun isPublicWebUrl(value: String): Boolean {
            if (value.length > 8192 || value.any { it.isISOControl() }) return false
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
            return uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    }
}
