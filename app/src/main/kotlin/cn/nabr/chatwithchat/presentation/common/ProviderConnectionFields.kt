package cn.nabr.chatwithchat.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.model.ApiProtocol
import cn.nabr.chatwithchat.data.model.ToolCallStrategy

/**
 * Connection configuration shared by onboarding and settings, split into cards by how often each part
 * is touched: what the provider is, how to reach it, and the advanced knobs most users never open.
 * The advanced card always sits last so it never pushes required fields down when it expands.
 *
 * @param credentialField optional content appended to the endpoint card (the add-platform screen
 * puts its API key here so the address and the key read as one unit).
 */
@Composable
fun ProviderConnectionFields(
    protocol: ApiProtocol,
    onProtocolChange: (ApiProtocol) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    isFullApiUrl: Boolean,
    onFullApiUrlChange: (Boolean) -> Unit,
    customHeaders: String,
    onCustomHeadersChange: (String) -> Unit,
    toolCallStrategy: ToolCallStrategy,
    onToolCallStrategyChange: (ToolCallStrategy) -> Unit,
    enabled: Boolean = true,
    credentialField: (@Composable () -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(PROVIDER_CARD_SPACING)) {
        ProviderCard {
            ProviderProtocolSelector(protocol, onProtocolChange, enabled)
            ProviderTextField(
                title = stringResource(R.string.platform_name),
                value = name,
                onValueChange = onNameChange,
                enabled = enabled
            )
        }
        ProviderCard {
            ProviderTextField(
                title = stringResource(R.string.api_url),
                value = apiUrl,
                onValueChange = onApiUrlChange,
                enabled = enabled
            )
            credentialField?.invoke()
        }
        ProviderAdvancedCard(
            isFullApiUrl = isFullApiUrl,
            onFullApiUrlChange = onFullApiUrlChange,
            customHeaders = customHeaders,
            onCustomHeadersChange = onCustomHeadersChange,
            toolCallStrategy = toolCallStrategy,
            onToolCallStrategyChange = onToolCallStrategyChange,
            enabled = enabled
        )
    }
}

@Composable
private fun ProviderCard(content: @Composable () -> Unit) {
    SettingsMaterialGroup(shape = ProviderCardShape) {
        Column(
            modifier = Modifier.padding(PROVIDER_CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(PROVIDER_FIELD_SPACING)
        ) {
            content()
        }
    }
}

@Composable
private fun ProviderTextField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean
) {
    Column {
        SettingsFieldTitle(title = title)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            shape = ProviderFieldShape,
            colors = settingsTextFieldColors()
        )
    }
}

/**
 * Collapsed by default. The header is the whole card's top row rather than a loose text button, so
 * the disclosure reads as part of the card and the chevron alone carries the open/closed state.
 */
@Composable
private fun ProviderAdvancedCard(
    isFullApiUrl: Boolean,
    onFullApiUrlChange: (Boolean) -> Unit,
    customHeaders: String,
    onCustomHeadersChange: (String) -> Unit,
    toolCallStrategy: ToolCallStrategy,
    onToolCallStrategyChange: (ToolCallStrategy) -> Unit,
    enabled: Boolean
) {
    val materialColors = settingsMaterialColors()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(PROVIDER_EXPAND_MILLIS, easing = FastOutSlowInEasing),
        label = "provider_advanced_chevron"
    )
    SettingsMaterialGroup(shape = ProviderCardShape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                .heightIn(min = 52.dp)
                .padding(horizontal = PROVIDER_CARD_PADDING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.provider_advanced),
                style = MaterialTheme.typography.bodyLarge,
                color = materialColors.primaryLabel,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = materialColors.secondaryLabel,
                modifier = Modifier.rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(PROVIDER_EXPAND_MILLIS, easing = FastOutSlowInEasing)) +
                fadeIn(tween(PROVIDER_EXPAND_MILLIS)),
            exit = shrinkVertically(tween(PROVIDER_EXPAND_MILLIS, easing = FastOutSlowInEasing)) +
                fadeOut(tween(PROVIDER_EXPAND_MILLIS / 2))
        ) {
            Column(
                modifier = Modifier.padding(
                    start = PROVIDER_CARD_PADDING,
                    end = PROVIDER_CARD_PADDING,
                    bottom = PROVIDER_CARD_PADDING
                ),
                verticalArrangement = Arrangement.spacedBy(PROVIDER_FIELD_SPACING)
            ) {
                ProviderToolStrategySelector(toolCallStrategy, onToolCallStrategyChange, enabled)
                SimulatedHeadersField(customHeaders, onCustomHeadersChange, enabled)
                FullApiUrlCheckbox(
                    checked = isFullApiUrl,
                    onCheckedChange = onFullApiUrlChange,
                    enabled = enabled,
                    helpInTooltip = true,
                    compact = true
                )
            }
        }
    }
}

/**
 * Starts at the same single-line height as every other field and grows only while focused, so the
 * collapsed card stays even and the multi-line hint appears when the user is actually typing.
 */
@Composable
private fun SimulatedHeadersField(
    customHeaders: String,
    onCustomHeadersChange: (String) -> Unit,
    enabled: Boolean
) {
    var focused by remember { mutableStateOf(false) }
    Column {
        SettingsFieldTitle(
            title = stringResource(R.string.simulated_request_headers),
            helpText = stringResource(R.string.simulated_request_headers_supporting)
        )
        OutlinedTextField(
            value = customHeaders,
            onValueChange = onCustomHeadersChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.simulated_request_headers_hint),
                    maxLines = if (focused) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .animateContentSize(tween(PROVIDER_EXPAND_MILLIS, easing = FastOutSlowInEasing)),
            minLines = if (focused) HEADERS_FOCUSED_MIN_LINES else 1,
            maxLines = if (focused) HEADERS_FOCUSED_MAX_LINES else 1,
            enabled = enabled,
            shape = ProviderFieldShape,
            colors = settingsTextFieldColors()
        )
    }
}

@Composable
fun ProviderProtocolSelector(protocol: ApiProtocol, onChange: (ApiProtocol) -> Unit, enabled: Boolean = true) {
    ProviderOptionSelector(
        title = stringResource(R.string.api_protocol),
        helpText = stringResource(protocol.descriptionRes()),
        selected = protocol,
        options = (ApiProtocol.selectable + protocol).distinct(),
        optionLabel = { it.displayName },
        onChange = onChange,
        enabled = enabled
    )
}

@Composable
fun ProviderToolStrategySelector(strategy: ToolCallStrategy, onChange: (ToolCallStrategy) -> Unit, enabled: Boolean = true) {
    ProviderOptionSelector(
        title = stringResource(R.string.tool_call_strategy),
        helpText = stringResource(R.string.tool_call_strategy_help),
        selected = strategy,
        options = ToolCallStrategy.entries,
        optionLabel = { stringResource(it.titleRes()) },
        onChange = onChange,
        enabled = enabled
    )
}

fun ApiProtocol.descriptionRes(): Int = when (this) {
    ApiProtocol.OPENAI_CHAT_COMPLETIONS -> R.string.protocol_chat_completions_help
    ApiProtocol.OPENAI_RESPONSES -> R.string.protocol_responses_help
    ApiProtocol.ANTHROPIC_MESSAGES -> R.string.protocol_anthropic_help
    ApiProtocol.GOOGLE_GENERATIVE_AI -> R.string.protocol_google_help
}

fun ToolCallStrategy.titleRes(): Int = when (this) {
    ToolCallStrategy.AUTO -> R.string.tool_strategy_auto
    ToolCallStrategy.NATIVE -> R.string.tool_strategy_native
    ToolCallStrategy.JSON -> R.string.tool_strategy_json
}

/**
 * The anchor keeps the shared field shape; the menu borrows the app's popup treatment (grouped
 * surface, hairline border, soft shadow) instead of the default tonal Material menu, and marks the
 * current option with a check so the choice is legible without reading the anchor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ProviderOptionSelector(
    title: String,
    helpText: String,
    selected: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onChange: (T) -> Unit,
    enabled: Boolean
) {
    val materialColors = settingsMaterialColors()
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(PROVIDER_EXPAND_MILLIS, easing = FastOutSlowInEasing),
        label = "provider_option_chevron"
    )
    Column {
        SettingsFieldTitle(title = title, helpText = helpText)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it }
        ) {
            OutlinedTextField(
                value = optionLabel(selected),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                enabled = enabled,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = materialColors.secondaryLabel,
                        modifier = Modifier.rotate(chevronRotation)
                    )
                },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
                shape = ProviderFieldShape,
                colors = settingsTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = ProviderMenuShape,
                containerColor = materialColors.grouped,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(0.5.dp, materialColors.separatorStrong)
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = optionLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = materialColors.primaryLabel,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Spacer(Modifier.size(18.dp))
                            }
                        },
                        onClick = {
                            onChange(option)
                            expanded = false
                        },
                        colors = settingsDropdownMenuItemColors(),
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

/** Shared with the API key field on the add-platform screen so every input has the same corners. */
val ProviderFieldShape = RoundedCornerShape(10.dp)

private val ProviderCardShape = RoundedCornerShape(16.dp)
private val ProviderMenuShape = RoundedCornerShape(14.dp)

private val PROVIDER_CARD_SPACING = 12.dp
private val PROVIDER_CARD_PADDING = 16.dp
private val PROVIDER_FIELD_SPACING = 12.dp
private const val PROVIDER_EXPAND_MILLIS = 220
private const val HEADERS_FOCUSED_MIN_LINES = 3
private const val HEADERS_FOCUSED_MAX_LINES = 6
