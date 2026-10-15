package cn.nabr.chatwithchat.presentation.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import cn.nabr.chatwithchat.data.model.DynamicTheme
import cn.nabr.chatwithchat.data.model.ThemeMode
import cn.nabr.chatwithchat.presentation.common.LocalIsDarkTheme
import cn.nabr.chatwithchat.presentation.common.LocalSettingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.SettingsCanvasDark
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialColorsDark
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialColorsLight
import cn.nabr.chatwithchat.presentation.common.THEME_TRANSITION_MILLIS

@Immutable
data class ExtendedColorScheme(
    val customColor1: ColorFamily,
    val chatGPTOfficialColor: ColorFamily,
    val customColor2: ColorFamily
)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark
)

private val mediumContrastLightColorScheme = lightColorScheme(
    primary = primaryLightMediumContrast,
    onPrimary = onPrimaryLightMediumContrast,
    primaryContainer = primaryContainerLightMediumContrast,
    onPrimaryContainer = onPrimaryContainerLightMediumContrast,
    secondary = secondaryLightMediumContrast,
    onSecondary = onSecondaryLightMediumContrast,
    secondaryContainer = secondaryContainerLightMediumContrast,
    onSecondaryContainer = onSecondaryContainerLightMediumContrast,
    tertiary = tertiaryLightMediumContrast,
    onTertiary = onTertiaryLightMediumContrast,
    tertiaryContainer = tertiaryContainerLightMediumContrast,
    onTertiaryContainer = onTertiaryContainerLightMediumContrast,
    error = errorLightMediumContrast,
    onError = onErrorLightMediumContrast,
    errorContainer = errorContainerLightMediumContrast,
    onErrorContainer = onErrorContainerLightMediumContrast,
    background = backgroundLightMediumContrast,
    onBackground = onBackgroundLightMediumContrast,
    surface = surfaceLightMediumContrast,
    onSurface = onSurfaceLightMediumContrast,
    surfaceVariant = surfaceVariantLightMediumContrast,
    onSurfaceVariant = onSurfaceVariantLightMediumContrast,
    outline = outlineLightMediumContrast,
    outlineVariant = outlineVariantLightMediumContrast,
    scrim = scrimLightMediumContrast,
    inverseSurface = inverseSurfaceLightMediumContrast,
    inverseOnSurface = inverseOnSurfaceLightMediumContrast,
    inversePrimary = inversePrimaryLightMediumContrast,
    surfaceDim = surfaceDimLightMediumContrast,
    surfaceBright = surfaceBrightLightMediumContrast,
    surfaceContainerLowest = surfaceContainerLowestLightMediumContrast,
    surfaceContainerLow = surfaceContainerLowLightMediumContrast,
    surfaceContainer = surfaceContainerLightMediumContrast,
    surfaceContainerHigh = surfaceContainerHighLightMediumContrast,
    surfaceContainerHighest = surfaceContainerHighestLightMediumContrast
)

private val highContrastLightColorScheme = lightColorScheme(
    primary = primaryLightHighContrast,
    onPrimary = onPrimaryLightHighContrast,
    primaryContainer = primaryContainerLightHighContrast,
    onPrimaryContainer = onPrimaryContainerLightHighContrast,
    secondary = secondaryLightHighContrast,
    onSecondary = onSecondaryLightHighContrast,
    secondaryContainer = secondaryContainerLightHighContrast,
    onSecondaryContainer = onSecondaryContainerLightHighContrast,
    tertiary = tertiaryLightHighContrast,
    onTertiary = onTertiaryLightHighContrast,
    tertiaryContainer = tertiaryContainerLightHighContrast,
    onTertiaryContainer = onTertiaryContainerLightHighContrast,
    error = errorLightHighContrast,
    onError = onErrorLightHighContrast,
    errorContainer = errorContainerLightHighContrast,
    onErrorContainer = onErrorContainerLightHighContrast,
    background = backgroundLightHighContrast,
    onBackground = onBackgroundLightHighContrast,
    surface = surfaceLightHighContrast,
    onSurface = onSurfaceLightHighContrast,
    surfaceVariant = surfaceVariantLightHighContrast,
    onSurfaceVariant = onSurfaceVariantLightHighContrast,
    outline = outlineLightHighContrast,
    outlineVariant = outlineVariantLightHighContrast,
    scrim = scrimLightHighContrast,
    inverseSurface = inverseSurfaceLightHighContrast,
    inverseOnSurface = inverseOnSurfaceLightHighContrast,
    inversePrimary = inversePrimaryLightHighContrast,
    surfaceDim = surfaceDimLightHighContrast,
    surfaceBright = surfaceBrightLightHighContrast,
    surfaceContainerLowest = surfaceContainerLowestLightHighContrast,
    surfaceContainerLow = surfaceContainerLowLightHighContrast,
    surfaceContainer = surfaceContainerLightHighContrast,
    surfaceContainerHigh = surfaceContainerHighLightHighContrast,
    surfaceContainerHighest = surfaceContainerHighestLightHighContrast
)

private val mediumContrastDarkColorScheme = darkColorScheme(
    primary = primaryDarkMediumContrast,
    onPrimary = onPrimaryDarkMediumContrast,
    primaryContainer = primaryContainerDarkMediumContrast,
    onPrimaryContainer = onPrimaryContainerDarkMediumContrast,
    secondary = secondaryDarkMediumContrast,
    onSecondary = onSecondaryDarkMediumContrast,
    secondaryContainer = secondaryContainerDarkMediumContrast,
    onSecondaryContainer = onSecondaryContainerDarkMediumContrast,
    tertiary = tertiaryDarkMediumContrast,
    onTertiary = onTertiaryDarkMediumContrast,
    tertiaryContainer = tertiaryContainerDarkMediumContrast,
    onTertiaryContainer = onTertiaryContainerDarkMediumContrast,
    error = errorDarkMediumContrast,
    onError = onErrorDarkMediumContrast,
    errorContainer = errorContainerDarkMediumContrast,
    onErrorContainer = onErrorContainerDarkMediumContrast,
    background = backgroundDarkMediumContrast,
    onBackground = onBackgroundDarkMediumContrast,
    surface = surfaceDarkMediumContrast,
    onSurface = onSurfaceDarkMediumContrast,
    surfaceVariant = surfaceVariantDarkMediumContrast,
    onSurfaceVariant = onSurfaceVariantDarkMediumContrast,
    outline = outlineDarkMediumContrast,
    outlineVariant = outlineVariantDarkMediumContrast,
    scrim = scrimDarkMediumContrast,
    inverseSurface = inverseSurfaceDarkMediumContrast,
    inverseOnSurface = inverseOnSurfaceDarkMediumContrast,
    inversePrimary = inversePrimaryDarkMediumContrast,
    surfaceDim = surfaceDimDarkMediumContrast,
    surfaceBright = surfaceBrightDarkMediumContrast,
    surfaceContainerLowest = surfaceContainerLowestDarkMediumContrast,
    surfaceContainerLow = surfaceContainerLowDarkMediumContrast,
    surfaceContainer = surfaceContainerDarkMediumContrast,
    surfaceContainerHigh = surfaceContainerHighDarkMediumContrast,
    surfaceContainerHighest = surfaceContainerHighestDarkMediumContrast
)

private val highContrastDarkColorScheme = darkColorScheme(
    primary = primaryDarkHighContrast,
    onPrimary = onPrimaryDarkHighContrast,
    primaryContainer = primaryContainerDarkHighContrast,
    onPrimaryContainer = onPrimaryContainerDarkHighContrast,
    secondary = secondaryDarkHighContrast,
    onSecondary = onSecondaryDarkHighContrast,
    secondaryContainer = secondaryContainerDarkHighContrast,
    onSecondaryContainer = onSecondaryContainerDarkHighContrast,
    tertiary = tertiaryDarkHighContrast,
    onTertiary = onTertiaryDarkHighContrast,
    tertiaryContainer = tertiaryContainerDarkHighContrast,
    onTertiaryContainer = onTertiaryContainerDarkHighContrast,
    error = errorDarkHighContrast,
    onError = onErrorDarkHighContrast,
    errorContainer = errorContainerDarkHighContrast,
    onErrorContainer = onErrorContainerDarkHighContrast,
    background = backgroundDarkHighContrast,
    onBackground = onBackgroundDarkHighContrast,
    surface = surfaceDarkHighContrast,
    onSurface = onSurfaceDarkHighContrast,
    surfaceVariant = surfaceVariantDarkHighContrast,
    onSurfaceVariant = onSurfaceVariantDarkHighContrast,
    outline = outlineDarkHighContrast,
    outlineVariant = outlineVariantDarkHighContrast,
    scrim = scrimDarkHighContrast,
    inverseSurface = inverseSurfaceDarkHighContrast,
    inverseOnSurface = inverseOnSurfaceDarkHighContrast,
    inversePrimary = inversePrimaryDarkHighContrast,
    surfaceDim = surfaceDimDarkHighContrast,
    surfaceBright = surfaceBrightDarkHighContrast,
    surfaceContainerLowest = surfaceContainerLowestDarkHighContrast,
    surfaceContainerLow = surfaceContainerLowDarkHighContrast,
    surfaceContainer = surfaceContainerDarkHighContrast,
    surfaceContainerHigh = surfaceContainerHighDarkHighContrast,
    surfaceContainerHighest = surfaceContainerHighestDarkHighContrast
)

val extendedLight = ExtendedColorScheme(
    customColor1 = ColorFamily(
        customColor1Light,
        onCustomColor1Light,
        customColor1ContainerLight,
        onCustomColor1ContainerLight
    ),
    chatGPTOfficialColor = ColorFamily(
        chatGPTOfficialColorLight,
        onChatGPTOfficialColorLight,
        chatGPTOfficialColorContainerLight,
        onChatGPTOfficialColorContainerLight
    ),
    customColor2 = ColorFamily(
        customColor2Light,
        onCustomColor2Light,
        customColor2ContainerLight,
        onCustomColor2ContainerLight
    )
)

val extendedDark = ExtendedColorScheme(
    customColor1 = ColorFamily(
        customColor1Dark,
        onCustomColor1Dark,
        customColor1ContainerDark,
        onCustomColor1ContainerDark
    ),
    chatGPTOfficialColor = ColorFamily(
        chatGPTOfficialColorDark,
        onChatGPTOfficialColorDark,
        chatGPTOfficialColorContainerDark,
        onChatGPTOfficialColorContainerDark
    ),
    customColor2 = ColorFamily(
        customColor2Dark,
        onCustomColor2Dark,
        customColor2ContainerDark,
        onCustomColor2ContainerDark
    )
)

val extendedLightMediumContrast = ExtendedColorScheme(
    customColor1 = ColorFamily(
        customColor1LightMediumContrast,
        onCustomColor1LightMediumContrast,
        customColor1ContainerLightMediumContrast,
        onCustomColor1ContainerLightMediumContrast
    ),
    chatGPTOfficialColor = ColorFamily(
        chatGPTOfficialColorLightMediumContrast,
        onChatGPTOfficialColorLightMediumContrast,
        chatGPTOfficialColorContainerLightMediumContrast,
        onChatGPTOfficialColorContainerLightMediumContrast
    ),
    customColor2 = ColorFamily(
        customColor2LightMediumContrast,
        onCustomColor2LightMediumContrast,
        customColor2ContainerLightMediumContrast,
        onCustomColor2ContainerLightMediumContrast
    )
)

val extendedLightHighContrast = ExtendedColorScheme(
    customColor1 = ColorFamily(
        customColor1LightHighContrast,
        onCustomColor1LightHighContrast,
        customColor1ContainerLightHighContrast,
        onCustomColor1ContainerLightHighContrast
    ),
    chatGPTOfficialColor = ColorFamily(
        chatGPTOfficialColorLightHighContrast,
        onChatGPTOfficialColorLightHighContrast,
        chatGPTOfficialColorContainerLightHighContrast,
        onChatGPTOfficialColorContainerLightHighContrast
    ),
    customColor2 = ColorFamily(
        customColor2LightHighContrast,
        onCustomColor2LightHighContrast,
        customColor2ContainerLightHighContrast,
        onCustomColor2ContainerLightHighContrast
    )
)

val extendedDarkMediumContrast = ExtendedColorScheme(
    customColor1 = ColorFamily(
        customColor1DarkMediumContrast,
        onCustomColor1DarkMediumContrast,
        customColor1ContainerDarkMediumContrast,
        onCustomColor1ContainerDarkMediumContrast
    ),
    chatGPTOfficialColor = ColorFamily(
        chatGPTOfficialColorDarkMediumContrast,
        onChatGPTOfficialColorDarkMediumContrast,
        chatGPTOfficialColorContainerDarkMediumContrast,
        onChatGPTOfficialColorContainerDarkMediumContrast
    ),
    customColor2 = ColorFamily(
        customColor2DarkMediumContrast,
        onCustomColor2DarkMediumContrast,
        customColor2ContainerDarkMediumContrast,
        onCustomColor2ContainerDarkMediumContrast
    )
)

val extendedDarkHighContrast = ExtendedColorScheme(
    customColor1 = ColorFamily(
        customColor1DarkHighContrast,
        onCustomColor1DarkHighContrast,
        customColor1ContainerDarkHighContrast,
        onCustomColor1ContainerDarkHighContrast
    ),
    chatGPTOfficialColor = ColorFamily(
        chatGPTOfficialColorDarkHighContrast,
        onChatGPTOfficialColorDarkHighContrast,
        chatGPTOfficialColorContainerDarkHighContrast,
        onChatGPTOfficialColorContainerDarkHighContrast
    ),
    customColor2 = ColorFamily(
        customColor2DarkHighContrast,
        onCustomColor2DarkHighContrast,
        customColor2ContainerDarkHighContrast,
        onCustomColor2ContainerDarkHighContrast
    )
)

@Immutable
data class ColorFamily(
    val color: Color,
    val onColor: Color,
    val colorContainer: Color,
    val onColorContainer: Color
)

val unspecified_scheme = ColorFamily(
    Color.Unspecified,
    Color.Unspecified,
    Color.Unspecified,
    Color.Unspecified
)

@Composable
fun ChatWithChatTheme(
    dynamicTheme: DynamicTheme = DynamicTheme.OFF,
    themeMode: ThemeMode = ThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    val useDynamicColor = dynamicTheme == DynamicTheme.ON
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val baseColorScheme = when {
        useDynamicColor -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        useDarkTheme -> darkScheme

        else -> lightScheme
    }
    val colorScheme = settingsColorScheme(baseColorScheme, useDarkTheme).animateOnThemeChange()
    val settingsColors = (if (useDarkTheme) SettingsMaterialColorsDark else SettingsMaterialColorsLight)
        .animateOnThemeChange()
    // 系统栏图标跟着插值中的画布亮度翻，而不是跟着目标值：t=0 就翻会让图标贴在还没变亮的
    // 背景上将近三百毫秒，等于短暂消失。跨过中点再翻，过渡前后两侧都有对比度。
    val isCanvasLight = colorScheme.background.luminance() > 0.5f
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isCanvasLight
                isAppearanceLightNavigationBars = isCanvasLight
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = {
            CompositionLocalProvider(
                // 目标明暗单独下发，让离散开关不必去读逐帧变化的颜色。
                LocalIsDarkTheme provides useDarkTheme,
                LocalSettingsMaterialColors provides settingsColors,
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = settingsColors.primaryLabel,
                    backgroundColor = settingsColors.primaryLabel.copy(alpha = 0.20f)
                ),
                content = content
            )
        }
    )
}

private val ThemeColorSpec = tween<Color>(
    durationMillis = THEME_TRANSITION_MILLIS,
    easing = FastOutSlowInEasing
)

@Composable
private fun animateThemeColor(target: Color, label: String): Color =
    animateColorAsState(targetValue = target, animationSpec = ThemeColorSpec, label = label).value

/**
 * 逐色插值整套 Material 配色。不少组件直读 `MaterialTheme.colorScheme`，而 app 的大部分
 * 表面走 [SettingsMaterialColors]，两套必须用同一条时长同步渐变，否则一屏之内会一半渐变
 * 一半瞬切。首帧 [animateColorAsState] 直接落在目标值上，所以冷启动不会有一次多余的动画。
 */
@Composable
private fun ColorScheme.animateOnThemeChange() = copy(
    primary = animateThemeColor(primary, "primary"),
    onPrimary = animateThemeColor(onPrimary, "onPrimary"),
    primaryContainer = animateThemeColor(primaryContainer, "primaryContainer"),
    onPrimaryContainer = animateThemeColor(onPrimaryContainer, "onPrimaryContainer"),
    secondary = animateThemeColor(secondary, "secondary"),
    onSecondary = animateThemeColor(onSecondary, "onSecondary"),
    secondaryContainer = animateThemeColor(secondaryContainer, "secondaryContainer"),
    onSecondaryContainer = animateThemeColor(onSecondaryContainer, "onSecondaryContainer"),
    tertiary = animateThemeColor(tertiary, "tertiary"),
    onTertiary = animateThemeColor(onTertiary, "onTertiary"),
    tertiaryContainer = animateThemeColor(tertiaryContainer, "tertiaryContainer"),
    onTertiaryContainer = animateThemeColor(onTertiaryContainer, "onTertiaryContainer"),
    error = animateThemeColor(error, "error"),
    onError = animateThemeColor(onError, "onError"),
    errorContainer = animateThemeColor(errorContainer, "errorContainer"),
    onErrorContainer = animateThemeColor(onErrorContainer, "onErrorContainer"),
    background = animateThemeColor(background, "background"),
    onBackground = animateThemeColor(onBackground, "onBackground"),
    surface = animateThemeColor(surface, "surface"),
    onSurface = animateThemeColor(onSurface, "onSurface"),
    surfaceVariant = animateThemeColor(surfaceVariant, "surfaceVariant"),
    onSurfaceVariant = animateThemeColor(onSurfaceVariant, "onSurfaceVariant"),
    outline = animateThemeColor(outline, "outline"),
    outlineVariant = animateThemeColor(outlineVariant, "outlineVariant"),
    scrim = animateThemeColor(scrim, "scrim"),
    inverseSurface = animateThemeColor(inverseSurface, "inverseSurface"),
    inverseOnSurface = animateThemeColor(inverseOnSurface, "inverseOnSurface"),
    inversePrimary = animateThemeColor(inversePrimary, "inversePrimary"),
    surfaceDim = animateThemeColor(surfaceDim, "surfaceDim"),
    surfaceBright = animateThemeColor(surfaceBright, "surfaceBright"),
    surfaceContainerLowest = animateThemeColor(surfaceContainerLowest, "surfaceContainerLowest"),
    surfaceContainerLow = animateThemeColor(surfaceContainerLow, "surfaceContainerLow"),
    surfaceContainer = animateThemeColor(surfaceContainer, "surfaceContainer"),
    surfaceContainerHigh = animateThemeColor(surfaceContainerHigh, "surfaceContainerHigh"),
    surfaceContainerHighest = animateThemeColor(surfaceContainerHighest, "surfaceContainerHighest")
)

@Composable
private fun SettingsMaterialColors.animateOnThemeChange() = SettingsMaterialColors(
    canvas = animateThemeColor(canvas, "canvas"),
    navigation = animateThemeColor(navigation, "navigation"),
    grouped = animateThemeColor(grouped, "grouped"),
    field = animateThemeColor(field, "field"),
    primaryLabel = animateThemeColor(primaryLabel, "primaryLabel"),
    secondaryLabel = animateThemeColor(secondaryLabel, "secondaryLabel"),
    tertiaryLabel = animateThemeColor(tertiaryLabel, "tertiaryLabel"),
    separator = animateThemeColor(separator, "separator"),
    separatorStrong = animateThemeColor(separatorStrong, "separatorStrong"),
    controlFill = animateThemeColor(controlFill, "controlFill")
)

private fun settingsColorScheme(base: ColorScheme, isDark: Boolean): ColorScheme {
    val canvas = if (isDark) SettingsCanvasDark else Color.White
    val grouped = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF8F8FA)
    val field = if (isDark) Color(0xFF2C2C2E) else Color.White
    val primaryLabel = if (isDark) Color.White else Color(0xFF1C1C1E)
    val secondaryLabel = if (isDark) Color(0xFFAEAEB2) else Color(0xFF6C6C70)
    val separator = if (isDark) Color(0x42545458) else Color(0x183C3C43)
    val separatorStrong = if (isDark) Color(0x66545458) else Color(0x303C3C43)
    val controlFill = if (isDark) Color(0xFF39393D) else Color(0xFFD1D1D6)
    return base.copy(
        primary = primaryLabel,
        onPrimary = canvas,
        primaryContainer = controlFill,
        onPrimaryContainer = primaryLabel,
        secondary = secondaryLabel,
        onSecondary = canvas,
        secondaryContainer = controlFill,
        onSecondaryContainer = primaryLabel,
        tertiary = primaryLabel,
        onTertiary = canvas,
        tertiaryContainer = grouped,
        onTertiaryContainer = primaryLabel,
        background = canvas,
        onBackground = primaryLabel,
        surface = canvas,
        onSurface = primaryLabel,
        surfaceVariant = grouped,
        onSurfaceVariant = secondaryLabel,
        inverseSurface = primaryLabel,
        inverseOnSurface = canvas,
        inversePrimary = canvas,
        outline = separatorStrong,
        outlineVariant = separator,
        surfaceDim = if (isDark) canvas else controlFill,
        surfaceBright = if (isDark) field else canvas,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = field,
        surfaceContainer = grouped,
        surfaceContainerHigh = controlFill,
        surfaceContainerHighest = controlFill
    )
}
