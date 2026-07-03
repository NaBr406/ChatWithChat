package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val MATH_JAX_BASE_URL = "file:///android_asset/mathjax/"
private const val MATH_JAX_RENDER_RETRY_DELAY_MILLIS = 50L
private const val MAX_MATH_JAX_RENDER_RETRIES = 80
private const val BACKGROUND_WEB_VIEW_WIDTH_PX = 4096
private const val BACKGROUND_WEB_VIEW_HEIGHT_PX = 4096
private const val BITMAP_PADDING_CSS_PX = 2
private const val MAX_RENDERED_BITMAP_DIMENSION_PX = 8192
private const val MATH_RENDER_CACHE_MAX_KIB = 24 * 1024

private const val MATH_JAX_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
<style>
html, body {
  margin: 0;
  padding: 0;
  background: transparent;
  overflow: visible;
  line-height: normal;
}
body {
  display: inline-block;
}
#root {
  display: inline-block;
  margin: 0;
  padding: 0;
  overflow: visible;
}
#root pre {
  margin: 0;
  white-space: pre-wrap;
  font-family: monospace;
}
mjx-container[jax="SVG"] {
  display: inline-block !important;
  margin: 0 !important;
  max-width: none !important;
  overflow: visible;
}
svg {
  overflow: visible;
  max-width: none !important;
}
</style>
<script>
(() => {
  const storageFactory = () => {
    const store = {};
    return {
      getItem(key) {
        return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : null;
      },
      setItem(key, value) {
        store[key] = String(value);
      },
      removeItem(key) {
        delete store[key];
      },
      clear() {
        Object.keys(store).forEach((key) => delete store[key]);
      }
    };
  };
  try {
    if (!window.localStorage) {
      Object.defineProperty(window, 'localStorage', {value: storageFactory(), configurable: true});
    }
  } catch (error) {
    Object.defineProperty(window, 'localStorage', {value: storageFactory(), configurable: true});
  }
})();
window.mathJaxReady = false;
window.MathJax = {
  options: {
    enableMenu: false,
    enableEnrichment: false,
    enableComplexity: false,
    enableSpeech: false,
    enableBraille: false,
    renderActions: {
      addMenu: [],
      checkLoading: [],
      assistiveMml: []
    },
    menuOptions: {
      settings: {
        enrich: false,
        speech: false,
        braille: false,
        assistiveMml: false
      }
    }
  },
  startup: {
    typeset: false,
    ready: () => {
      MathJax.startup.defaultReady();
      window.mathJaxReady = true;
    }
  },
  svg: {
    fontCache: 'none'
  }
};
</script>
<script defer src="tex-svg.js"></script>
<script>
window.renderMathToSvg = function(expression, displayMode, textColor, textOpacity, fontSizePx) {
  if (!window.mathJaxReady || !window.MathJax || typeof MathJax.tex2svg !== 'function') {
    return 'loading';
  }

  const root = document.getElementById('root');
  document.body.style.color = textColor;
  document.body.style.opacity = textOpacity;
  document.body.style.fontSize = fontSizePx + 'px';
  root.className = displayMode ? 'display' : 'inline';

  try {
    const node = MathJax.tex2svg(expression, { display: displayMode });
    root.replaceChildren(node);

    const container = root.firstElementChild || root;
    const svg = container.querySelector('svg');
    if (!svg) {
      return {
        status: 'error',
        message: 'MathJax did not produce an SVG node'
      };
    }

    const svgRect = svg.getBoundingClientRect();
    const containerRect = container.getBoundingClientRect();
    const width = Math.max(Math.ceil(svgRect.width), Math.ceil(containerRect.width), 1);
    const height = Math.max(Math.ceil(svgRect.height), Math.ceil(containerRect.height), 1);
    const clonedSvg = svg.cloneNode(true);
    clonedSvg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    clonedSvg.setAttribute('width', width + 'px');
    clonedSvg.setAttribute('height', height + 'px');
    clonedSvg.setAttribute('color', textColor);
    clonedSvg.setAttribute('fill', textColor);
    clonedSvg.setAttribute('stroke', textColor);
    if (textOpacity < 1) {
      clonedSvg.setAttribute('opacity', String(textOpacity));
    }

    return {
      status: 'ok',
      width,
      height,
      svg: clonedSvg.outerHTML.replace(/currentColor/g, textColor)
    };
  } catch (error) {
    return {
      status: 'error',
      message: String(error && error.message ? error.message : error)
    };
  }
};
</script>
</head>
<body>
<div id="root" class="inline"></div>
</body>
</html>
"""

internal data class MathRenderRequest(
    val tex: String,
    val displayMode: Boolean,
    val fontSizePx: Int,
    val textColorCss: String,
    val textAlpha: Float,
    val density: Float,
    val fontScale: Float,
    val minimumHeightCssPx: Int
)

internal data class MathRenderResult(
    val bitmap: Bitmap?,
    val widthPx: Int,
    val heightPx: Int,
    val widthCssPx: Int,
    val heightCssPx: Int
)

private data class RawMathSvgResult(
    val svg: String,
    val widthCssPx: Int,
    val heightCssPx: Int
)

internal object MathJaxRenderService {
    private val renderMutex = Mutex()
    private val renderCache = object : LruCache<String, MathRenderResult>(MATH_RENDER_CACHE_MAX_KIB) {
        override fun sizeOf(
            key: String,
            value: MathRenderResult
        ): Int = (value.bitmap?.allocationByteCount ?: 1024)
            .div(1024)
            .coerceAtLeast(1)
    }

    @SuppressLint("StaticFieldLeak")
    private var renderer: BackgroundMathJaxRenderer? = null

    fun getCachedResult(request: MathRenderRequest): MathRenderResult? = renderCache.get(request.cacheKey())

    suspend fun render(
        context: Context,
        request: MathRenderRequest
    ): MathRenderResult {
        getCachedResult(request)?.let { return it }

        return renderMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                getCachedResult(request)
            }?.let { return@withLock it }

            val rawResult = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    rendererFor(context.applicationContext).renderSvg(request)
                }
            }.getOrElse {
                return@withLock fallbackResult(request)
            }

            val result = withContext(Dispatchers.Default) {
                rasterizeSvg(rawResult, request)
            } ?: fallbackResult(request)

            withContext(Dispatchers.Main.immediate) {
                renderCache.put(request.cacheKey(), result)
            }
            result
        }
    }

    private fun rendererFor(applicationContext: Context): BackgroundMathJaxRenderer {
        val currentRenderer = renderer
        if (currentRenderer != null) return currentRenderer

        return BackgroundMathJaxRenderer(applicationContext).also { renderer = it }
    }
}

internal fun MathRenderRequest.cacheKey(): String = buildString(tex.length + 64) {
    append(displayMode)
    append('|')
    append(fontSizePx)
    append('|')
    append(textColorCss)
    append('|')
    append((textAlpha * 1000).roundToInt())
    append('|')
    append((density * 1000).roundToInt())
    append('|')
    append((fontScale * 1000).roundToInt())
    append('|')
    append(tex)
}

@SuppressLint("SetJavaScriptEnabled")
private class BackgroundMathJaxRenderer(context: Context) {
    private val pageLoadedContinuations = mutableListOf<kotlinx.coroutines.CancellableContinuation<Unit>>()
    private var pageLoaded = false
    private val webView = WebView(context.applicationContext).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        isHorizontalScrollBarEnabled = false
        isLongClickable = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setOnLongClickListener { true }

        settings.allowContentAccess = false
        settings.allowFileAccess = true
        settings.blockNetworkLoads = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.javaScriptEnabled = true

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                pageLoaded = true
                pageLoadedContinuations.toList().forEach { continuation ->
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                pageLoadedContinuations.clear()
            }
        }

        measure(
            View.MeasureSpec.makeMeasureSpec(BACKGROUND_WEB_VIEW_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(BACKGROUND_WEB_VIEW_HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, BACKGROUND_WEB_VIEW_WIDTH_PX, BACKGROUND_WEB_VIEW_HEIGHT_PX)

        loadDataWithBaseURL(
            MATH_JAX_BASE_URL,
            MATH_JAX_HTML,
            "text/html",
            "utf-8",
            null
        )
    }

    suspend fun renderSvg(request: MathRenderRequest): RawMathSvgResult {
        waitForPageLoad()

        var retryCount = 0
        while (true) {
            val rawResult = evaluateJavascript(buildRenderScript(request))
            if (rawResult == "\"loading\"") {
                if (retryCount++ >= MAX_MATH_JAX_RENDER_RETRIES) {
                    throw IllegalStateException("MathJax did not become ready")
                }
                delay(MATH_JAX_RENDER_RETRY_DELAY_MILLIS)
                continue
            }

            parseRawMathSvgResult(rawResult)?.let { return it }
            throw IllegalStateException("MathJax render failed")
        }
    }

    private suspend fun waitForPageLoad() {
        if (pageLoaded) return

        suspendCancellableCoroutine { continuation ->
            pageLoadedContinuations += continuation
            continuation.invokeOnCancellation {
                pageLoadedContinuations.remove(continuation)
            }
        }
    }

    private suspend fun evaluateJavascript(script: String): String? = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { rawResult ->
            if (continuation.isActive) {
                continuation.resume(rawResult)
            }
        }
    }
}

private fun buildRenderScript(request: MathRenderRequest): String = """
    (function() {
      if (typeof window.renderMathToSvg !== 'function') {
        return null;
      }
      return window.renderMathToSvg(
        ${JSONObject.quote(request.tex)},
        ${request.displayMode},
        ${JSONObject.quote(request.textColorCss)},
        ${request.textAlpha},
        ${request.fontSizePx}
      );
    })();
""".trimIndent()

private fun parseRawMathSvgResult(rawResult: String?): RawMathSvgResult? {
    val result = rawResult
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: return null

    return runCatching {
        val json = JSONObject(result)
        if (json.optString("status") != "ok") return@runCatching null

        RawMathSvgResult(
            svg = json.getString("svg"),
            widthCssPx = json.getInt("width"),
            heightCssPx = json.getInt("height")
        )
    }.getOrNull()
}

private fun rasterizeSvg(
    rawResult: RawMathSvgResult,
    request: MathRenderRequest
): MathRenderResult? = runCatching {
    val scale = request.density.coerceAtLeast(1f)
    val paddingPx = (BITMAP_PADDING_CSS_PX * scale).roundToInt().coerceAtLeast(1)
    val contentWidthPx = (rawResult.widthCssPx * scale).roundToInt().coerceAtLeast(1)
    val contentHeightPx = (rawResult.heightCssPx * scale).roundToInt().coerceAtLeast(1)
    val bitmapWidthPx = (contentWidthPx + paddingPx * 2).coerceAtMost(MAX_RENDERED_BITMAP_DIMENSION_PX)
    val bitmapHeightPx = (contentHeightPx + paddingPx * 2).coerceAtMost(MAX_RENDERED_BITMAP_DIMENSION_PX)
    if (
        bitmapWidthPx < contentWidthPx + paddingPx * 2 ||
        bitmapHeightPx < contentHeightPx + paddingPx * 2
    ) {
        return@runCatching null
    }

    val svg = SVG.getFromString(rawResult.svg)
    svg.setDocumentWidth(contentWidthPx.toFloat())
    svg.setDocumentHeight(contentHeightPx.toFloat())

    val bitmap = Bitmap.createBitmap(bitmapWidthPx, bitmapHeightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.translate(paddingPx.toFloat(), paddingPx.toFloat())
    svg.renderToCanvas(canvas)

    MathRenderResult(
        bitmap = bitmap,
        widthPx = bitmapWidthPx,
        heightPx = bitmapHeightPx,
        widthCssPx = rawResult.widthCssPx + BITMAP_PADDING_CSS_PX * 2,
        heightCssPx = rawResult.heightCssPx + BITMAP_PADDING_CSS_PX * 2
    )
}.getOrNull()

private fun fallbackResult(request: MathRenderRequest): MathRenderResult = MathRenderResult(
    bitmap = null,
    widthPx = 0,
    heightPx = 0,
    widthCssPx = 0,
    heightCssPx = request.minimumHeightCssPx
)
