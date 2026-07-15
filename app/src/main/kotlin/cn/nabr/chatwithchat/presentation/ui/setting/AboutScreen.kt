package cn.nabr.chatwithchat.presentation.ui.setting

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.common.SettingItem
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(
    onNavigationClick: () -> Unit,
    onNavigationToLicense: () -> Unit
) {
    val scrollState = rememberScrollState()
    val materialColors = settingsMaterialColors()
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    val clipboardManager = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val githubLink = stringResource(R.string.github_link)
    val releasesLink = stringResource(R.string.f_droid_link)
    val actionsLink = stringResource(R.string.play_store_link)
    val bugReportLink = stringResource(R.string.bug_report_link)
    val feedbackLink = stringResource(R.string.feedback_link)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.about),
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = materialColors.canvas
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            SettingsMaterialGroup {
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.version),
                    description = "v$version",
                    onItemClick = { scope.launch { clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("v$version", "v$version"))) } },
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    showDivider = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_info),
                            contentDescription = stringResource(R.string.version_icon)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.license),
                    description = stringResource(R.string.license_description),
                    onItemClick = onNavigationToLicense,
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    showDivider = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_license),
                            contentDescription = stringResource(R.string.license_icon)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.github),
                    onItemClick = { uriHandler.openUri(githubLink) },
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    showDivider = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_github),
                            contentDescription = stringResource(R.string.github_icon)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.f_droid),
                    onItemClick = { uriHandler.openUri(releasesLink) },
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    showDivider = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_github),
                            contentDescription = stringResource(R.string.f_droid_icon)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.play_store),
                    onItemClick = { uriHandler.openUri(actionsLink) },
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    showDivider = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_github),
                            contentDescription = stringResource(R.string.play_store_icon)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.bug_report),
                    description = stringResource(R.string.bug_report_description),
                    onItemClick = { uriHandler.openUri(bugReportLink) },
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    showDivider = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_bug_report),
                            contentDescription = stringResource(R.string.bug_report_icon)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.feedback),
                    description = stringResource(R.string.feedback_description),
                    onItemClick = { uriHandler.openUri(feedbackLink) },
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_feedback),
                            contentDescription = stringResource(R.string.feedback_icon)
                        )
                    }
                )
            }
        }
    }
}
