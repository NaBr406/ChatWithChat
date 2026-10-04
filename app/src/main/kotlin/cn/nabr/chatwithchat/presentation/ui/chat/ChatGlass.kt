package cn.nabr.chatwithchat.presentation.ui.chat

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

@Composable
internal fun ChatGlassScope(
    backdrop: LayerBackdrop = rememberLayerBackdrop(),
    content: @Composable () -> Unit
) {
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier.onGloballyPositioned {
            windowOrigin = it.positionOnScreen() - it.positionInWindow()
        }
    ) {
        CompositionLocalProvider(LocalChatGlass provides ChatGlassContext(backdrop, windowOrigin)) {
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
