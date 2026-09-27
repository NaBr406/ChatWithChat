package cn.nabr.chatwithchat.presentation.ui.memory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsButtonColors
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryExportScreen(
    memoryViewModel: MemoryViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val uiState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val materialColors = settingsMaterialColors()
    val emptyMarkdownText = stringResource(R.string.memory_export_empty_markdown)
    val fileName = remember { memoryExportFileName() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var hasRequestedFreshExport by rememberSaveable { mutableStateOf(false) }
    val isLoading = !hasRequestedFreshExport || uiState.isExportLoading
    val isReady = !isLoading && uiState.exportMarkdown != null

    LaunchedEffect(memoryViewModel) {
        hasRequestedFreshExport = true
        memoryViewModel.exportMarkdown()
    }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = CreateMemoryMarkdownDocumentContract()
    ) { uri ->
        val markdown = uiState.exportMarkdown
        if (uri == null || markdown == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    saveMemoryMarkdown(
                        context = context,
                        uri = uri,
                        markdown = markdown
                    )
                }.isSuccess
            }
            snackbarHostState.showSnackbar(
                context.getString(
                    if (saved) R.string.memory_export_saved else R.string.memory_export_save_failed
                )
            )
        }
    }

    Scaffold(
        modifier = Modifier.settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.memory_export_title),
                onNavigationClick = onNavigationClick
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = materialColors.grouped,
                    contentColor = materialColors.primaryLabel
                )
            }
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.memory_export_description),
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel
            )

            SettingsMaterialGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            color = materialColors.primaryLabel
                        )
                    }
                } else {
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Text(
                            text = uiState.exportMarkdown.orEmpty().ifBlank { emptyMarkdownText },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (uiState.exportMarkdown.isNullOrBlank()) {
                                materialColors.secondaryLabel
                            } else {
                                materialColors.primaryLabel
                            }
                        )
                    }
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                onClick = { documentLauncher.launch(fileName) },
                enabled = isReady,
                shape = RoundedCornerShape(12.dp),
                colors = settingsButtonColors()
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.memory_export_save_to_documents))
            }
        }
    }
}

private class CreateMemoryMarkdownDocumentContract : ActivityResultContract<String, Uri?>() {

    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MEMORY_EXPORT_MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, input)
            // DocumentsUI honors this on AOSP-compatible devices; unsupported OEM pickers may ignore it.
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    PRIMARY_STORAGE_DOCUMENTS_AUTHORITY,
                    "primary:${Environment.DIRECTORY_DOCUMENTS}"
                )
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

private const val PRIMARY_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
