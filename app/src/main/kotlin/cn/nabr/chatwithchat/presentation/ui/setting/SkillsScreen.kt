package cn.nabr.chatwithchat.presentation.ui.setting

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.textskill.InstalledSkillPackage
import cn.nabr.chatwithchat.data.textskill.RegisteredTextSkillVariant
import cn.nabr.chatwithchat.data.textskill.SkillCapability
import cn.nabr.chatwithchat.data.textskill.SkillCapabilityRequest
import cn.nabr.chatwithchat.data.textskill.SkillStandardStatus
import cn.nabr.chatwithchat.data.textskill.TextSkillCompatibility
import cn.nabr.chatwithchat.data.textskill.TextSkillContentPreview
import cn.nabr.chatwithchat.presentation.common.AppleRed
import cn.nabr.chatwithchat.presentation.common.HigActionDialog
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsTextButtonColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Local text-skill library: one card per installed package, ZIP import in the top bar.
 *
 * Entry paths, standard status and capability diagnostics deliberately do not appear in the list —
 * they belong to a single skill rather than to the choice between skills, so they live in the
 * content preview dialog that opens when a card (or, for multi-skill packages, a variant row) is
 * tapped. That keeps the list scannable when a package ships a dozen variants.
 */
@Composable
fun SkillsScreen(
    skillsViewModel: SkillsViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val packages by skillsViewModel.packages.collectAsStateWithLifecycle()
    val isImporting by skillsViewModel.isImporting.collectAsStateWithLifecycle()
    val message by skillsViewModel.message.collectAsStateWithLifecycle()
    val importPreview by skillsViewModel.importPreview.collectAsStateWithLifecycle()
    val deletePreview by skillsViewModel.deletePreview.collectAsStateWithLifecycle()
    val contentPreview by skillsViewModel.contentPreview.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let(skillsViewModel::requestImport)
    }
    val launchImportPicker = {
        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
    }

    LaunchedEffect(message) {
        message?.let { text ->
            snackbarHostState.showSnackbar(text)
            skillsViewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.skills),
                onNavigationClick = onNavigationClick,
                actions = {
                    IconButton(
                        enabled = !isImporting,
                        onClick = launchImportPicker
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.import_skill)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isImporting) {
                item(key = "import-progress") {
                    SkillImportProgress()
                }
            }
            if (packages.isEmpty()) {
                item(key = "empty") {
                    SkillsEmptyState(onImportClick = launchImportPicker)
                }
            } else {
                items(packages, key = InstalledSkillPackage::packageId) { packageItem ->
                    SkillPackageCard(
                        packageItem = packageItem,
                        onVariantClick = { variant -> skillsViewModel.viewVariant(packageItem, variant) },
                        onDeleteClick = { skillsViewModel.requestDelete(packageItem) }
                    )
                }
            }
        }
    }

    importPreview?.let { state ->
        SkillImportPreviewDialog(
            state = state,
            onConfirm = skillsViewModel::confirmImport,
            onDismiss = skillsViewModel::cancelImport
        )
    }

    deletePreview?.let { state ->
        HigActionDialog(
            title = stringResource(R.string.skill_delete_confirm_title),
            message = if (state.affectedBindingCount > 0) {
                stringResource(R.string.skill_delete_binding_notice, state.affectedBindingCount)
            } else {
                stringResource(R.string.skill_delete_confirmation, state.packageItem.sourceName)
            },
            primaryActionLabel = stringResource(R.string.delete),
            onPrimaryAction = skillsViewModel::confirmDelete,
            onDismissRequest = skillsViewModel::cancelDelete,
            secondaryActionLabel = stringResource(R.string.cancel),
            onSecondaryAction = skillsViewModel::cancelDelete,
            primaryActionColor = AppleRed
        )
    }

    contentPreview?.let { state ->
        SkillContentPreviewDialog(
            state = state,
            onDismiss = skillsViewModel::closeContentPreview
        )
    }
}

@Composable
private fun SkillPackageCard(
    packageItem: InstalledSkillPackage,
    onVariantClick: (RegisteredTextSkillVariant) -> Unit,
    onDeleteClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    val singleVariant = packageItem.variants.singleOrNull()
    val headline = singleVariant?.displayName ?: packageItem.sourceName
    val supporting = singleVariant?.description
    SettingsMaterialGroup {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (singleVariant == null) {
                            Modifier
                        } else {
                            Modifier.clickable { onVariantClick(singleVariant) }
                        }
                    )
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = materialColors.secondaryLabel
                    )
                }
                Spacer(modifier = Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        color = materialColors.primaryLabel
                    )
                    if (!supporting.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = supporting,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = materialColors.secondaryLabel
                        )
                    }
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = materialColors.secondaryLabel
                    )
                }
            }
            if (singleVariant == null) {
                packageItem.variants.forEach { variant ->
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp),
                        thickness = 0.5.dp,
                        color = materialColors.separator
                    )
                    SkillVariantRow(
                        variant = variant,
                        onClick = { onVariantClick(variant) }
                    )
                }
            }
            if (packageItem.hasScripts || packageItem.requestedCapabilities.isNotEmpty()) {
                SkillTextOnlyNotice()
            }
        }
    }
}

@Composable
private fun SkillVariantRow(
    variant: RegisteredTextSkillVariant,
    onClick: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(start = 64.dp, top = 10.dp, end = 16.dp, bottom = 10.dp)
    ) {
        Text(
            text = variant.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = materialColors.primaryLabel
        )
        if (variant.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = variant.description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel
            )
        }
    }
}

@Composable
private fun SkillTextOnlyNotice() {
    val materialColors = settingsMaterialColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = materialColors.tertiaryLabel
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.skill_text_only_warning),
            style = MaterialTheme.typography.bodySmall,
            color = materialColors.tertiaryLabel
        )
    }
}

@Composable
private fun SkillImportProgress() {
    val materialColors = settingsMaterialColors()
    SettingsMaterialGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.skill_importing),
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.primaryLabel
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun SkillsEmptyState(onImportClick: () -> Unit) {
    val materialColors = settingsMaterialColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = materialColors.tertiaryLabel
        )
        Text(
            modifier = Modifier.padding(top = 10.dp),
            text = stringResource(R.string.skills_empty),
            style = MaterialTheme.typography.titleSmall,
            color = materialColors.primaryLabel
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = stringResource(R.string.skills_empty_description),
            style = MaterialTheme.typography.bodySmall,
            color = materialColors.secondaryLabel
        )
        TextButton(
            onClick = onImportClick,
            colors = settingsTextButtonColors()
        ) {
            Text(stringResource(R.string.import_skill))
        }
    }
}

@Composable
private fun SkillImportPreviewDialog(
    state: SkillsViewModel.ImportPreviewState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    HigActionDialog(
        title = stringResource(R.string.skill_import_preview_title),
        message = state.preview.packageItem.sourceName,
        primaryActionLabel = stringResource(R.string.confirm),
        onPrimaryAction = onConfirm,
        onDismissRequest = onDismiss,
        secondaryActionLabel = stringResource(R.string.cancel),
        onSecondaryAction = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.preview.packageItem.variants.forEach { variant ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = variant.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = materialColors.primaryLabel
                    )
                    Text(
                        text = skillVariantDetails(variant),
                        style = MaterialTheme.typography.labelSmall,
                        color = materialColors.secondaryLabel
                    )
                    if (variant.requestedCapabilities.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.skill_unavailable_capabilities,
                                capabilityLabels(variant.requestedCapabilities)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = materialColors.tertiaryLabel
                        )
                    }
                }
            }
            if (state.affectedBindingCount > 0) {
                Text(
                    text = stringResource(
                        R.string.skill_replace_binding_notice,
                        state.affectedBindingCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = materialColors.secondaryLabel
                )
            }
        }
    }
}

@Composable
private fun SkillContentPreviewDialog(
    state: SkillsViewModel.ContentPreviewState,
    onDismiss: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(state) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_FEEDBACK_MILLIS)
            copied = false
        }
    }
    HigActionDialog(
        title = state.variant.displayName,
        message = skillVariantDetails(state.variant),
        primaryActionLabel = stringResource(R.string.close),
        onPrimaryAction = onDismiss,
        onDismissRequest = onDismiss,
        secondaryActionLabel = if (copied) {
            stringResource(R.string.code_copied)
        } else {
            stringResource(R.string.copy_text)
        },
        onSecondaryAction = {
            val plainText = state.content.asPlainText()
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText(state.variant.displayName, plainText))
                )
            }
            copied = true
        }
    ) {
        if (state.variant.requestedCapabilities.isNotEmpty()) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                text = stringResource(
                    R.string.skill_unavailable_capabilities,
                    capabilityLabels(state.variant.requestedCapabilities)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = materialColors.tertiaryLabel
            )
        }
        SkillContentBlock(
            path = state.content.entryPath,
            content = state.content.entryMarkdown
        )
        state.content.references.forEach { reference ->
            SkillContentBlock(
                path = reference.path,
                content = reference.content
            )
        }
    }
}

@Composable
private fun SkillContentBlock(
    path: String,
    content: String
) {
    val materialColors = settingsMaterialColors()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Text(
            text = path,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = materialColors.tertiaryLabel
        )
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(materialColors.field, shape)
                .border(0.5.dp, materialColors.separator, shape)
                .padding(12.dp),
            text = content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = materialColors.primaryLabel
        )
    }
}

@Composable
private fun skillVariantDetails(variant: RegisteredTextSkillVariant): String = stringResource(
    R.string.skill_variant_details,
    variant.entryPath,
    standardStatusLabel(variant.standardStatus),
    compatibilityLabel(variant.textCompatibility)
)

@Composable
private fun standardStatusLabel(status: SkillStandardStatus): String = when (status) {
    SkillStandardStatus.STANDARD -> stringResource(R.string.skill_standard_status)
    SkillStandardStatus.CLAUDE_COMPATIBLE -> stringResource(R.string.skill_claude_compatible_status)
    SkillStandardStatus.ADAPTER_EXTENSION -> stringResource(R.string.skill_adapter_extension_status)
    SkillStandardStatus.NON_STANDARD_FRONTMATTER -> stringResource(R.string.skill_non_standard_status)
}

@Composable
private fun compatibilityLabel(compatibility: TextSkillCompatibility): String = when (compatibility) {
    TextSkillCompatibility.TEXT_ONLY -> stringResource(R.string.skill_text_only_compatible)
    TextSkillCompatibility.TEXT_ONLY_WITH_UNAVAILABLE_CAPABILITIES -> {
        stringResource(R.string.skill_text_only_partial)
    }
}

@Composable
private fun capabilityLabel(capability: SkillCapability): String = when (capability) {
    SkillCapability.TEXT_REFERENCE_READ -> stringResource(R.string.skill_capability_text_reference)
    SkillCapability.HOST_TOOL_CATALOG -> stringResource(R.string.skill_capability_host_tools)
    SkillCapability.MCP_TOOL_CALL -> stringResource(R.string.skill_capability_mcp_tool_call)
    SkillCapability.MCP_RESOURCE_READ -> stringResource(R.string.skill_capability_mcp_resource_read)
    SkillCapability.MCP_PROMPT_READ -> stringResource(R.string.skill_capability_mcp_prompt_read)
}

@Composable
private fun capabilityLabels(capabilities: List<SkillCapabilityRequest>): String {
    val labels = ArrayList<String>(capabilities.size)
    for (request in capabilities) {
        labels += capabilityLabel(request.capability)
    }
    return labels.joinToString()
}

private fun TextSkillContentPreview.asPlainText(): String = buildString {
    append(entryPath)
    append('\n')
    append(entryMarkdown)
    references.forEach { reference ->
        append("\n\n")
        append(reference.path)
        append('\n')
        append(reference.content)
    }
}

private const val COPY_FEEDBACK_MILLIS = 1_600L
