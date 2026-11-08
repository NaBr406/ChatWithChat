package cn.nabr.chatwithchat.presentation.ui.chat

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.presentation.common.isSettingsDarkTheme
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal data class ChatGlassContext(
    val backdrop: Backdrop,
    val captureBackdrop: LayerBackdrop?,
    val windowOrigin: Offset
)

internal val LocalChatGlass = staticCompositionLocalOf<ChatGlassContext?> { null }

@Composable
internal fun Modifier.chatTopBarBackground(): Modifier {
    val canvas = settingsMaterialColors().canvas
    return drawWithCache {
        val scrim = Brush.verticalGradient(
            0f to canvas.copy(alpha = 0.96f),
            0.35f to canvas.copy(alpha = 0.85f),
            0.75f to canvas.copy(alpha = 0.3f),
            1f to canvas.copy(alpha = 0f)
        )
        onDrawBehind { drawRect(scrim) }
    }
}

private const val GLASS_MIN_TOTAL_MEMORY_BYTES = 3L * 1024 * 1024 * 1024

/**
 * Whether this device should draw the blurred glass at all.
 *
 * Every glass surface re-blurs the recorded chat layer on each frame the list changes, which during
 * streaming is every frame. Low-memory devices and battery saver get the solid fallback instead.
 * Checked when the screen is composed, so toggling battery saver applies the next time it opens.
 */
internal fun deviceSupportsChatGlass(context: Context): Boolean {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (activityManager?.isLowRamDevice == true) return false
    if (powerManager?.isPowerSaveMode == true) return false
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memoryInfo)
    return memoryInfo.totalMem == 0L || memoryInfo.totalMem >= GLASS_MIN_TOTAL_MEMORY_BYTES
}

@Composable
internal fun rememberDeviceSupportsChatGlass(): Boolean {
    val context = LocalContext.current
    return remember(context) { deviceSupportsChatGlass(context) }
}

@Composable
internal fun ChatGlassScope(
    backdrop: LayerBackdrop = rememberLayerBackdrop(),
    enabled: Boolean = true,
    captureLiveContent: Boolean = true,
    content: @Composable () -> Unit
) {
    val glassActive = enabled && rememberDeviceSupportsChatGlass()
    val ambientBackdrop = if (captureLiveContent) null else rememberAmbientChatBackdrop()
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier.onGloballyPositioned {
            windowOrigin = it.positionOnScreen() - it.positionInWindow()
        }
    ) {
        val context = if (glassActive) {
            ChatGlassContext(
                backdrop = ambientBackdrop ?: backdrop,
                captureBackdrop = backdrop.takeIf { captureLiveContent },
                windowOrigin = windowOrigin
            )
        } else {
            null
        }
        CompositionLocalProvider(LocalChatGlass provides context) {
            content()
        }
    }
}

@Composable
internal fun Modifier.chatSelectionGlass(shape: Shape): Modifier {
    val context = LocalChatGlass.current
    val backdrop = remember(context) {
        context?.backdrop?.let { backdrop ->
            if (backdrop.isCoordinatesDependent) {
                WindowAlignedBackdrop(backdrop, context.windowOrigin)
            } else {
                backdrop
            }
        }
    }
    return composerGlass(backdrop, shape)
}

@Composable
private fun rememberAmbientChatBackdrop(): Backdrop {
    val colors = settingsMaterialColors()
    val isDark = isSettingsDarkTheme()
    return remember(colors, isDark) { AmbientChatBackdrop(colors.canvas, isDark) }
}

/**
 * A backdrop that cannot contain a WebView. The glass still receives the same blur, refraction and
 * vibrancy pipeline, but samples a generated ambient field instead of the recorded chat subtree.
 */
private class AmbientChatBackdrop(
    private val canvas: Color,
    private val isDark: Boolean
) : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        drawRect(canvas)
        val radius = size.minDimension.coerceAtLeast(1f)
        val glowAlpha = if (isDark) 1f else 0.42f

        fun bloom(center: Offset, color: Color, alpha: Float, scale: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to color.copy(alpha = alpha * glowAlpha),
                        0.48f to color.copy(alpha = alpha * 0.32f * glowAlpha),
                        1f to Color.Transparent
                    ),
                    center = center,
                    radius = radius * scale
                ),
                center = center,
                radius = radius * scale
            )
        }

        bloom(
            center = Offset(size.width * 0.08f, size.height * 0.12f),
            color = AMBIENT_BLUE,
            alpha = if (isDark) 0.26f else 0.12f,
            scale = 1.15f
        )
        bloom(
            center = Offset(size.width * 0.92f, size.height * 0.78f),
            color = AMBIENT_WARM,
            alpha = if (isDark) 0.13f else 0.08f,
            scale = 0.78f
        )
        bloom(
            center = Offset(size.width * 0.56f, size.height * 0.38f),
            color = AMBIENT_MINT,
            alpha = if (isDark) 0.11f else 0.055f,
            scale = 0.9f
        )
    }

    private companion object {
        val AMBIENT_BLUE = Color(0xFF2E6BFF)
        val AMBIENT_WARM = Color(0xFFFFA45B)
        val AMBIENT_MINT = Color(0xFF4DAA98)
    }
}

@Composable
internal fun ChatGlassIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(40.dp).chatSelectionGlass(CircleShape))
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatGlassSelectionSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = Color.Transparent,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .chatSelectionGlass(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        ) {
            BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
            content()
        }
    }
}
