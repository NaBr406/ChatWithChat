package cn.nabr.chatwithchat.presentation.common

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import cn.nabr.chatwithchat.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class SettingsMaterialColors(
    val canvas: Color,
    val navigation: Color,
    val grouped: Color,
    val field: Color,
    val primaryLabel: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val separatorStrong: Color,
    val controlFill: Color
)

@Composable
fun settingsMaterialColors(): SettingsMaterialColors {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return if (isLight) {
        SettingsMaterialColors(
            canvas = Color.White,
            navigation = Color.White,
            grouped = Color(0xFFF8F8FA),
            field = Color.White.copy(alpha = 0.82f),
            primaryLabel = Color(0xFF1C1C1E),
            secondaryLabel = Color(0xFF6C6C70),
            tertiaryLabel = Color(0xFFAEAEB2),
            separator = Color(0x183C3C43),
            separatorStrong = Color(0x303C3C43),
            controlFill = Color(0xFFD1D1D6)
        )
    } else {
        SettingsMaterialColors(
            canvas = Color.Black,
            navigation = Color.Black,
            grouped = Color(0xFF1C1C1E),
            field = Color(0xFF2C2C2E).copy(alpha = 0.88f),
            primaryLabel = Color.White,
            secondaryLabel = Color(0xFFAEAEB2),
            tertiaryLabel = Color(0xFF636366),
            separator = Color(0x42545458),
            separatorStrong = Color(0x66545458),
            controlFill = Color(0xFF39393D)
        )
    }
}

/**
 * 绘制设置画布，并在深色模式下，在画布后方绘制数朵缓慢漂移的蓝色光晕。

* 这些光晕以*根*坐标布局，并移回调用者自身的空间，因此每个绘制画布的表面——Scaffold 主体、不透明的顶栏、聊天输入框——都落在同一连续区域中。如果改为按元素定位，则每个元素左上角的渐变都会重新开始，从而在每条栏下方留下明显的接缝。

* 相位由无限帧时钟驱动，而非 [androidx.compose.animation.core.Animatable]，因此在屏幕间导航时，动画会从上一屏幕停止的位置继续，而不是跳回循环起点。相位存储在一个浮点状态中，该状态仅在 [drawBehind] 内部读取，这样每一帧只触发一次绘制失效，而不会触发重组。

* 调用者还必须清除自身组件在顶部绘制的内容——`Scaffold(containerColor = Color.Transparent)`、`topAppBarColors(containerColor = Color.Transparent)`。这些内容是在修饰符链内部绘制的，因此不透明的容器颜色会覆盖光晕。
 */
@Composable
fun Modifier.settingsCanvasBackground(): Modifier {
    val materialColors = settingsMaterialColors()
    val isDark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    if (!isDark) return background(materialColors.canvas)

    val phase = remember { mutableFloatStateOf(0f) }
    val geometry = remember { mutableStateOf(CanvasGlowGeometry.Unset) }
    val context = LocalContext.current
    val animated = remember(context) { context.animatorsEnabled() }
    if (animated) {
        LaunchedEffect(Unit) {
            var lastFrameMillis = Long.MIN_VALUE
            while (true) {
                withInfiniteAnimationFrameMillis { frameMillis ->
                    // 移动得足够慢，每秒约20次更新与每帧一次几乎无法区分，而且能将空闲时的重绘率降低三分之二。
                    if (frameMillis - lastFrameMillis >= GLOW_FRAME_INTERVAL_MILLIS) {
                        lastFrameMillis = frameMillis
                        phase.floatValue = (frameMillis % GLOW_CYCLE_MILLIS) / GLOW_CYCLE_MILLIS.toFloat()
                    }
                }
            }
        }
    }
    return background(materialColors.canvas)
        .onGloballyPositioned { coordinates ->
            geometry.value = CanvasGlowGeometry(
                origin = coordinates.positionInRoot(),
                field = coordinates.findRootCoordinates().size.toSize()
            )
        }
        .drawBehind {
            // Draw modifiers are not clipped to their layout bounds, and a short bar's blooms are
            // centred well outside it. Unclipped they would also paint over the body below, which
            // double-adds under BlendMode.Plus.
            clipRect { drawCanvasGlow(phase.floatValue, geometry.value) }
        }
}

private fun Context.animatorsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private fun DrawScope.drawCanvasGlow(phase: Float, geometry: CanvasGlowGeometry) {
    val field = geometry.field
    if (field.minDimension <= 0f) return
    // Scaling off the narrow edge keeps a bloom roughly one screen-width across on any device;
    // scaling off the long edge makes three of them overlap everywhere and read as a flat wash.
    val baseRadius = field.minDimension * GLOW_RADIUS_FRACTION
    SettingsGlowBlooms.forEach { bloom ->
        // Integer harmonics keep every bloom back at its starting point when the phase wraps,
        // so the loop has no visible seam.
        val angle = (phase * bloom.harmonic + bloom.phaseOffset) * 2f * PI.toFloat()
        val center = Offset(
            x = field.width * (bloom.x + bloom.driftX * sin(angle)) - geometry.origin.x,
            y = field.height * (bloom.y + bloom.driftY * cos(angle)) - geometry.origin.y
        )
        val radius = baseRadius * bloom.radiusScale
        drawCircle(
            brush = Brush.radialGradient(
                // 直线从核心到透明的渐变在中间半径处保留了过多的光。额外的一档
                // 将衰减向内收拢，这样泛光仍然读作泛光，而不是一种色调。
                colorStops = arrayOf(
                    0f to SettingsGlowColor.copy(alpha = bloom.alpha),
                    0.45f to SettingsGlowColor.copy(alpha = bloom.alpha * 0.38f),
                    1f to Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center,
            blendMode = BlendMode.Plus
        )
    }
}

@Immutable
private data class CanvasGlowGeometry(
    val origin: Offset,
    val field: Size
) {
    companion object {
        val Unset = CanvasGlowGeometry(Offset.Zero, Size.Zero)
    }
}

@Immutable
private data class SettingsGlowBloom(
    val x: Float,
    val y: Float,
    val driftX: Float,
    val driftY: Float,
    val radiusScale: Float,
    val alpha: Float,
    val harmonic: Int,
    val phaseOffset: Float
)

private const val GLOW_CYCLE_MILLIS = 48_000L
private const val GLOW_FRAME_INTERVAL_MILLIS = 50L
private const val GLOW_RADIUS_FRACTION = 0.58f

private val SettingsGlowColor = Color(0xFF2E6BFF)

// Drift is a fraction of the field's width/height, so a bloom's centre may leave the viewport as
// long as it stays within roughly its own radius of the edge — past that a dark hole opens up on
// the side it left behind.
private val SettingsGlowBlooms = listOf(
    SettingsGlowBloom(
        x = 0.16f,
        y = 0.10f,
        driftX = 0.22f,
        driftY = 0.14f,
        radiusScale = 1f,
        alpha = 0.135f,
        harmonic = 1,
        phaseOffset = 0f
    ),
    SettingsGlowBloom(
        x = 0.88f,
        y = 0.36f,
        driftX = 0.18f,
        driftY = 0.20f,
        radiusScale = 0.8f,
        alpha = 0.105f,
        harmonic = 2,
        phaseOffset = 0.33f
    ),
    SettingsGlowBloom(
        x = 0.44f,
        y = 0.94f,
        driftX = 0.26f,
        driftY = 0.12f,
        radiusScale = 0.9f,
        alpha = 0.0825f,
        harmonic = 3,
        phaseOffset = 0.66f
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopAppBar(
    title: String,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    navigationEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val materialColors = settingsMaterialColors()
    CenterAlignedTopAppBar(
        // The bar stays opaque — it paints the canvas itself — so scrolled content cannot show
        // through it, but the glow runs on underneath without a seam at the bar's bottom edge.
        modifier = modifier.settingsCanvasBackground(),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        navigationIcon = {
            IconButton(
                enabled = navigationEnabled,
                onClick = onNavigationClick
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.go_back)
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = materialColors.primaryLabel,
            navigationIconContentColor = materialColors.primaryLabel,
            actionIconContentColor = materialColors.primaryLabel
        )
    )
}

@Composable
fun SettingsMaterialGroup(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    showBorder: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val materialColors = settingsMaterialColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = materialColors.grouped,
        border = if (showBorder) BorderStroke(0.5.dp, materialColors.separator) else null,
        content = { Column(content = content) }
    )
}

@Composable
fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = settingsMaterialColors().primaryLabel,
    unfocusedTextColor = settingsMaterialColors().primaryLabel,
    disabledTextColor = settingsMaterialColors().tertiaryLabel,
    focusedContainerColor = settingsMaterialColors().field,
    unfocusedContainerColor = settingsMaterialColors().field,
    disabledContainerColor = settingsMaterialColors().field,
    focusedBorderColor = settingsMaterialColors().primaryLabel,
    unfocusedBorderColor = settingsMaterialColors().separatorStrong,
    disabledBorderColor = settingsMaterialColors().separator,
    focusedLabelColor = settingsMaterialColors().primaryLabel,
    unfocusedLabelColor = settingsMaterialColors().secondaryLabel,
    disabledLabelColor = settingsMaterialColors().tertiaryLabel,
    cursorColor = settingsMaterialColors().primaryLabel
)

@Composable
fun settingsSwitchColors() = settingsMaterialColors().let { materialColors ->
    SwitchDefaults.colors(
        checkedThumbColor = materialColors.canvas,
        checkedTrackColor = materialColors.primaryLabel,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = materialColors.canvas,
        uncheckedTrackColor = materialColors.controlFill,
        uncheckedBorderColor = Color.Transparent
    )
}

@Composable
fun settingsInteractiveColor(): Color = settingsMaterialColors().primaryLabel

@Composable
fun settingsButtonColors() = settingsMaterialColors().let { materialColors ->
    ButtonDefaults.buttonColors(
        containerColor = materialColors.primaryLabel,
        contentColor = materialColors.canvas,
        disabledContainerColor = materialColors.controlFill,
        disabledContentColor = materialColors.tertiaryLabel
    )
}

@Composable
fun settingsTextButtonColors() = settingsMaterialColors().let { materialColors ->
    ButtonDefaults.textButtonColors(
        contentColor = materialColors.primaryLabel,
        disabledContentColor = materialColors.tertiaryLabel
    )
}

@Composable
fun settingsCheckboxColors() = settingsMaterialColors().let { materialColors ->
    CheckboxDefaults.colors(
        checkedColor = materialColors.primaryLabel,
        uncheckedColor = materialColors.secondaryLabel,
        checkmarkColor = materialColors.canvas,
        disabledCheckedColor = materialColors.controlFill,
        disabledUncheckedColor = materialColors.tertiaryLabel,
        disabledIndeterminateColor = materialColors.controlFill
    )
}

@Composable
fun settingsRadioButtonColors() = settingsMaterialColors().let { materialColors ->
    RadioButtonDefaults.colors(
        selectedColor = materialColors.primaryLabel,
        unselectedColor = materialColors.secondaryLabel,
        disabledSelectedColor = materialColors.controlFill,
        disabledUnselectedColor = materialColors.tertiaryLabel
    )
}

@Composable
fun settingsSliderColors() = settingsMaterialColors().let { materialColors ->
    SliderDefaults.colors(
        thumbColor = materialColors.primaryLabel,
        activeTrackColor = materialColors.primaryLabel,
        activeTickColor = materialColors.canvas,
        inactiveTrackColor = materialColors.controlFill,
        inactiveTickColor = materialColors.secondaryLabel,
        disabledThumbColor = materialColors.tertiaryLabel,
        disabledActiveTrackColor = materialColors.controlFill,
        disabledActiveTickColor = materialColors.canvas,
        disabledInactiveTrackColor = materialColors.controlFill,
        disabledInactiveTickColor = materialColors.tertiaryLabel
    )
}

@Composable
fun settingsDropdownMenuItemColors() = settingsMaterialColors().let { materialColors ->
    androidx.compose.material3.MenuDefaults.itemColors(
        textColor = materialColors.primaryLabel,
        leadingIconColor = materialColors.secondaryLabel,
        trailingIconColor = materialColors.secondaryLabel,
        disabledTextColor = materialColors.tertiaryLabel,
        disabledLeadingIconColor = materialColors.tertiaryLabel,
        disabledTrailingIconColor = materialColors.tertiaryLabel
    )
}

val AppleGreen = Color(0xFF34C759)
val AppleIndigo = Color(0xFF5856D6)
val AppleOrange = Color(0xFFFF9500)
val ApplePurple = Color(0xFFAF52DE)
val AppleRed = Color(0xFFFF3B30)
val AppleGray = Color(0xFF8E8E93)
