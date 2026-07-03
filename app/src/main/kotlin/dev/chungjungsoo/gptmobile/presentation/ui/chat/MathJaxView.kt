package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val DISPLAY_MATH_HEIGHT_CACHE_SIZE = 512

private object MathJaxDisplayHeightCache {
    private val cache = LruCache<String, Int>(DISPLAY_MATH_HEIGHT_CACHE_SIZE)

    fun get(request: MathRenderRequest): Int? = cache.get(request.cacheKey())

    fun put(
        request: MathRenderRequest,
        heightCssPx: Int
    ) {
        cache.put(request.cacheKey(), heightCssPx)
    }
}

@Composable
internal fun InlineMathView(
    tex: String,
    useMathJax: Boolean = false
) {
    val fallbackText = remember(tex) { tex.toReadableMathText() }
    if (!useMathJax) {
        NativeInlineMathView(fallbackText)
        return
    }

    val request = rememberMathRenderRequest(
        tex = tex,
        displayMode = false,
        minimumHeightCssPx = rememberInlineMinimumHeightCssPx()
    )
    val renderResult by rememberMathRenderResult(request)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart
    ) {
        RenderedMathImage(
            result = renderResult,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            fallback = { NativeInlineMathView(fallbackText) }
        )
    }
}

@Composable
internal fun DisplayMathView(
    tex: String,
    useMathJax: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (!useMathJax) {
        NativeDisplayMathView(
            tex = tex,
            modifier = modifier
        )
        return
    }

    val minimumHeightCssPx = rememberDisplayMinimumHeightCssPx(tex)
    val request = rememberMathRenderRequest(
        tex = tex,
        displayMode = true,
        minimumHeightCssPx = minimumHeightCssPx
    )
    val renderResult by rememberMathRenderResult(request)
    val measuredHeightCssPx = renderResult
        ?.heightCssPx
        ?.coerceAtLeast(minimumHeightCssPx)
        ?: MathJaxDisplayHeightCache.get(request)
        ?: minimumHeightCssPx

    LaunchedEffect(request, renderResult?.heightCssPx) {
        renderResult?.heightCssPx?.let { heightCssPx ->
            MathJaxDisplayHeightCache.put(request, heightCssPx.coerceAtLeast(minimumHeightCssPx))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = measuredHeightCssPx.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val bitmapResult = renderResult?.takeIf { it.bitmap != null }
        if (bitmapResult != null) {
            RenderedMathImage(
                result = bitmapResult,
                modifier = Modifier
                    .width(bitmapResult.widthCssPx.dp)
                    .height(bitmapResult.heightCssPx.dp),
                contentScale = ContentScale.Fit,
                fallback = {
                    NativeDisplayMathView(
                        tex = tex,
                        modifier = Modifier.wrapContentHeight(),
                        contentPadding = false
                    )
                }
            )
        } else {
            NativeDisplayMathView(
                tex = tex,
                modifier = Modifier.wrapContentHeight(),
                contentPadding = false
            )
        }
    }
}

@Composable
private fun rememberMathRenderRequest(
    tex: String,
    displayMode: Boolean,
    minimumHeightCssPx: Int
): MathRenderRequest {
    val density = LocalDensity.current
    val textColor = LocalContentColor.current
    val fontSize = MaterialTheme.typography.bodyMedium.fontSize
        .takeIf { it.type != TextUnitType.Unspecified }
        ?: 16.sp
    val fontSizeCssPx = remember(fontSize, density.fontScale) {
        (fontSize.value * density.fontScale).roundToInt().coerceAtLeast(1)
    }
    val textColorCss = remember(textColor) {
        formatCssColor(textColor)
    }
    val textAlpha = remember(textColor) {
        textColor.alpha.coerceIn(0f, 1f)
    }

    return remember(
        tex,
        displayMode,
        fontSizeCssPx,
        textColorCss,
        textAlpha,
        density.density,
        density.fontScale,
        minimumHeightCssPx
    ) {
        MathRenderRequest(
            tex = tex,
            displayMode = displayMode,
            fontSizePx = fontSizeCssPx,
            textColorCss = textColorCss,
            textAlpha = textAlpha,
            density = density.density,
            fontScale = density.fontScale,
            minimumHeightCssPx = minimumHeightCssPx
        )
    }
}

@Composable
private fun rememberInlineMinimumHeightCssPx(): Int {
    val density = LocalDensity.current
    val fontSize = MaterialTheme.typography.bodyMedium.fontSize
        .takeIf { it.type != TextUnitType.Unspecified }
        ?: 16.sp

    return remember(fontSize, density.fontScale) {
        (fontSize.value * density.fontScale * 1.4f).roundToInt().coerceAtLeast(1)
    }
}

@Composable
private fun rememberDisplayMinimumHeightCssPx(tex: String): Int {
    val density = LocalDensity.current
    val fontSize = MaterialTheme.typography.bodyMedium.fontSize
        .takeIf { it.type != TextUnitType.Unspecified }
        ?: 16.sp
    val fontSizeCssPx = remember(fontSize, density.fontScale) {
        (fontSize.value * density.fontScale).roundToInt().coerceAtLeast(1)
    }

    return remember(tex, fontSizeCssPx) {
        estimateDisplayMathMinimumHeightPx(tex, fontSizeCssPx)
    }
}

@Composable
private fun rememberMathRenderResult(request: MathRenderRequest): State<MathRenderResult?> {
    val applicationContext = LocalContext.current.applicationContext

    return produceState(
        initialValue = MathJaxRenderService.getCachedResult(request),
        key1 = applicationContext,
        key2 = request
    ) {
        MathJaxRenderService.getCachedResult(request)?.let { cachedResult ->
            value = cachedResult
            return@produceState
        }

        value = runCatching {
            MathJaxRenderService.render(applicationContext, request)
        }.getOrNull()
    }
}

@Composable
private fun RenderedMathImage(
    result: MathRenderResult?,
    modifier: Modifier,
    contentScale: ContentScale,
    fallback: @Composable () -> Unit
) {
    val bitmap = result?.bitmap
    if (bitmap == null) {
        fallback()
        return
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale
    )
}

@Composable
private fun NativeInlineMathView(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun NativeDisplayMathView(
    tex: String,
    modifier: Modifier = Modifier,
    contentPadding: Boolean = true
) {
    val displayText = remember(tex) { tex.toReadableMathText() }
    val baseModifier = modifier
        .fillMaxWidth()
        .wrapContentHeight()
    val textModifier = if (contentPadding) {
        baseModifier.padding(horizontal = 12.dp, vertical = 10.dp)
    } else {
        baseModifier
    }

    Text(
        text = displayText,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center
        ),
        color = LocalContentColor.current,
        textAlign = TextAlign.Center,
        modifier = textModifier
    )
}

private fun formatCssColor(color: Color): String {
    val red = (color.red * 255).roundToInt().coerceIn(0, 255)
    val green = (color.green * 255).roundToInt().coerceIn(0, 255)
    val blue = (color.blue * 255).roundToInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(red, green, blue)
}

private fun estimateDisplayMathMinimumHeightPx(
    tex: String,
    fontSizePx: Int
): Int {
    val lineCount = tex.split("\\\\").size.coerceAtLeast(1)
    val containsTallOperators = listOf(
        "\\frac",
        "\\dfrac",
        "\\tfrac",
        "\\sum",
        "\\prod",
        "\\int",
        "\\oint",
        "\\lim",
        "\\begin",
        "\\left",
        "\\right",
        "\\over"
    ).any(tex::contains)
    val baseEmHeight = when {
        lineCount >= 3 -> 7.5f
        lineCount == 2 -> 5.8f
        containsTallOperators -> 4.8f
        else -> 3.2f
    }
    return (fontSizePx * baseEmHeight).roundToInt().coerceAtLeast(1)
}

private data class MathScriptContent(
    val content: String,
    val nextIndex: Int
)

private val mathSymbolReplacements = linkedMapOf(
    "\\rightarrow" to "->",
    "\\left" to "",
    "\\right" to "",
    "\\," to " ",
    "\\;" to " ",
    "\\:" to " ",
    "\\!" to "",
    "\\pi" to "pi",
    "\\theta" to "theta",
    "\\alpha" to "alpha",
    "\\beta" to "beta",
    "\\gamma" to "gamma",
    "\\delta" to "delta",
    "\\epsilon" to "epsilon",
    "\\lambda" to "lambda",
    "\\mu" to "mu",
    "\\sigma" to "sigma",
    "\\phi" to "phi",
    "\\omega" to "omega",
    "\\infty" to "infinity",
    "\\partial" to "partial",
    "\\nabla" to "nabla",
    "\\cdot" to ".",
    "\\times" to "x",
    "\\leq" to "<=",
    "\\le" to "<=",
    "\\geq" to ">=",
    "\\ge" to ">=",
    "\\neq" to "!=",
    "\\pm" to "+/-",
    "\\to" to "->",
    "\\in" to "in"
)

private fun String.toReadableMathText(): String {
    var output = trim()
    if (output.isBlank()) return output

    output = output
        .replace("""\\(?:dfrac|tfrac|frac)\{([^{}]+)\}\{([^{}]+)\}""".toRegex(), "($1)/($2)")
        .replace("""\\sqrt\{([^{}]+)\}""".toRegex(), "sqrt($1)")
        .replace("\\\\", "\n")

    mathSymbolReplacements.forEach { (source, replacement) ->
        output = output.replace(source, replacement)
    }

    output = replaceMathScripts(output) ?: output
    return output
        .replace("""\\([a-zA-Z]+)""".toRegex()) { matchResult -> matchResult.groupValues[1] }
        .replace("{", "")
        .replace("}", "")
        .replace("""[ \t]+""".toRegex(), " ")
        .lineSequence()
        .joinToString("\n") { it.trim() }
        .trim()
}

private fun replaceMathScripts(input: String): String? {
    val output = StringBuilder(input.length)
    var index = 0

    while (index < input.length) {
        val marker = input[index]
        if (marker != '^' && marker != '_') {
            output.append(marker)
            index++
            continue
        }

        val script = readMathScriptContent(input, index + 1) ?: return null
        val markerText = if (marker == '^') "^" else "_"
        output.append(markerText)
        output.append(script.content)
        index = script.nextIndex
    }

    return output.toString()
}

private fun readMathScriptContent(
    input: String,
    startIndex: Int
): MathScriptContent? {
    if (startIndex >= input.length) return null
    if (input[startIndex] != '{') {
        return MathScriptContent(
            content = input[startIndex].toString(),
            nextIndex = startIndex + 1
        )
    }

    val endIndex = input.indexOf('}', startIndex + 1)
    if (endIndex == -1) return null

    val content = input.substring(startIndex + 1, endIndex)
    if (content.isBlank() || content.length > 8) return null

    return MathScriptContent(
        content = content,
        nextIndex = endIndex + 1
    )
}
