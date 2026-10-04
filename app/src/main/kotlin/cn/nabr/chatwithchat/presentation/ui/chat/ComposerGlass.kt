package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.presentation.common.isSettingsDarkTheme
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

@Composable
internal fun Modifier.composerGlass(backdrop: Backdrop?, shape: Shape): Modifier {
    val colors = settingsMaterialColors()
    val tint = colors.grouped.copy(alpha = if (isSettingsDarkTheme()) 0.42f else 0.58f)
    return if (backdrop == null) {
        background(colors.grouped, shape).border(0.5.dp, colors.separatorStrong, shape)
    } else {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(6.dp.toPx())
                lens(10.dp.toPx(), 16.dp.toPx())
            },
            onDrawSurface = { drawRect(tint) }
        )
    }
}

// Popup coordinates belong to a separate window; align its backdrop with the composer window.
internal class WindowAlignedBackdrop(
    private val source: Backdrop,
    private val sourceWindowOrigin: Offset
) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val windowOffset = coordinates.positionOnScreen() - coordinates.positionInWindow() - sourceWindowOrigin
        translate(-windowOffset.x, -windowOffset.y) {
            with(source) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}
