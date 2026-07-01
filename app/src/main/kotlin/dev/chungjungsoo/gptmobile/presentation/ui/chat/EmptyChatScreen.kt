package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.ui.home.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmptyChatScreen(
    homeViewModel: HomeViewModel,
    onOpenDrawer: () -> Unit,
    onStartChat: (String) -> Unit,
    onAddProvider: () -> Unit
) {
    val lastSelectedModel by homeViewModel.lastSelectedModel.collectAsStateWithLifecycle()
    val availableChatModels by homeViewModel.availableChatModels.collectAsStateWithLifecycle()
    val currentModelOptions = remember(availableChatModels, lastSelectedModel) {
        buildModelSelectionOptions(
            models = availableChatModels,
            selectedPlatformUid = lastSelectedModel?.platformUid,
            selectedModel = lastSelectedModel?.model
        )
    }
    val currentModelLabel = currentModelOptions.firstOrNull { it.selected }?.label
        ?: currentModelOptions.firstOrNull()?.label
        ?: stringResource(R.string.chat_models)
    val inputState = rememberTextFieldState()
    val canChat = currentModelOptions.isNotEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    if (canChat) {
                        ModelSelectionMenu(
                            label = currentModelLabel,
                            options = currentModelOptions,
                            enabled = true,
                            onOptionSelected = { option ->
                                homeViewModel.updateLastSelectedModel(option.platformUid, option.model)
                            }
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.empty_chat_no_platforms),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = stringResource(R.string.open_chat_history)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.empty_chat_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            if (canChat) {
                ChatComposer(
                    inputState = inputState,
                    chatEnabled = true,
                    sendButtonEnabled = true,
                    showAttachmentButton = false,
                    onSendButtonClick = {
                        val prompt = inputState.text.toString().trim()
                        if (prompt.isNotEmpty()) {
                            onStartChat(prompt)
                        }
                    }
                )
            } else {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .height(56.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    onClick = onAddProvider
                ) {
                    Text(text = stringResource(R.string.add_platform))
                }
            }
        }
    }
}
