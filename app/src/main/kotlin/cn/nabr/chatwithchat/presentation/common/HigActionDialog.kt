package cn.nabr.chatwithchat.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun HigActionDialog(
    title: String,
    message: String?,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    isDismissible: Boolean = true,
    isPrimaryActionEnabled: Boolean = true,
    primaryActionColor: Color = AppleBlue,
    secondaryActionColor: Color = AppleBlue,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val materialColors = settingsMaterialColors()
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val maxDialogHeight = (windowHeight - 48.dp).coerceAtLeast(240.dp)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = isDismissible,
            dismissOnClickOutside = isDismissible,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = modifier
                    .widthIn(max = 320.dp)
                    .heightIn(max = maxDialogHeight)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = materialColors.grouped,
                contentColor = materialColors.primaryLabel,
                border = BorderStroke(0.5.dp, materialColors.separatorStrong),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp
            ) {
                Column {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(weight = 1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = materialColors.primaryLabel,
                            textAlign = TextAlign.Center
                        )
                        message?.takeIf { it.isNotBlank() }?.let { messageText ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = messageText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = materialColors.secondaryLabel,
                                textAlign = TextAlign.Center
                            )
                        }
                        detail?.takeIf { it.isNotBlank() }?.let { detailText ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = detailText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = materialColors.primaryLabel,
                                textAlign = TextAlign.Center
                            )
                        }
                        content()
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = materialColors.separatorStrong)
                    val secondaryAction = onSecondaryAction
                    if (secondaryActionLabel != null && secondaryAction != null) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DialogAction(
                                label = secondaryActionLabel,
                                color = secondaryActionColor,
                                onClick = secondaryAction,
                                modifier = Modifier.weight(1f)
                            )
                            VerticalDivider(
                                modifier = Modifier.height(50.dp),
                                thickness = 0.5.dp,
                                color = materialColors.separatorStrong
                            )
                            DialogAction(
                                label = primaryActionLabel,
                                color = primaryActionColor,
                                onClick = onPrimaryAction,
                                enabled = isPrimaryActionEnabled,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        DialogAction(
                            label = primaryActionLabel,
                            color = primaryActionColor,
                            onClick = onPrimaryAction,
                            enabled = isPrimaryActionEnabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) color else settingsMaterialColors().tertiaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
