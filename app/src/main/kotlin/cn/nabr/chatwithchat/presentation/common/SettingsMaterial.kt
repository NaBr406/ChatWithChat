package cn.nabr.chatwithchat.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R

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
        modifier = modifier,
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
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBackIos,
                    contentDescription = stringResource(R.string.go_back)
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = materialColors.navigation)
    )
}

@Composable
fun SettingsMaterialGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val materialColors = settingsMaterialColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = materialColors.grouped,
        border = BorderStroke(0.5.dp, materialColors.separator),
        content = { Column(content = content) }
    )
}

@Composable
fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = settingsMaterialColors().field,
    unfocusedContainerColor = settingsMaterialColors().field,
    focusedBorderColor = AppleBlue,
    unfocusedBorderColor = settingsMaterialColors().separatorStrong
)

@Composable
fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AppleGreen,
    checkedBorderColor = AppleGreen,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = settingsMaterialColors().controlFill,
    uncheckedBorderColor = Color.Transparent
)

val AppleBlue = Color(0xFF007AFF)
val AppleGreen = Color(0xFF34C759)
val AppleIndigo = Color(0xFF5856D6)
val AppleOrange = Color(0xFFFF9500)
val ApplePurple = Color(0xFFAF52DE)
val AppleRed = Color(0xFFFF3B30)
val AppleGray = Color(0xFF8E8E93)
