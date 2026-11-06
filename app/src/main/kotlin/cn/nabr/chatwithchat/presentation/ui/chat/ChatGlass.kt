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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal data class ChatGlassContext(val backdrop: LayerBackdrop, val windowOrigin: Offset)

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
    content: @Composable () -> Unit
) {
    val glassActive = enabled && rememberDeviceSupportsChatGlass()
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier.onGloballyPositioned {
            windowOrigin = it.positionOnScreen() - it.positionInWindow()
        }
    ) {
        CompositionLocalProvider(LocalChatGlass provides if (glassActive) ChatGlassContext(backdrop, windowOrigin) else null) {
            content()
        }
    }
}

@Composable
internal fun Modifier.chatSelectionGlass(shape: Shape): Modifier {
    val context = LocalChatGlass.current
    val backdrop = remember(context) {
        context?.let { WindowAlignedBackdrop(it.backdrop, it.windowOrigin) }
    }
    return composerGlass(backdrop, shape)
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
