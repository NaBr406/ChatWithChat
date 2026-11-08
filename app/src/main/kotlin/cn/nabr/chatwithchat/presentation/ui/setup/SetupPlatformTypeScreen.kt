package cn.nabr.chatwithchat.presentation.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.model.ApiProtocol
import cn.nabr.chatwithchat.presentation.common.SettingsHelpTooltipIcon
import cn.nabr.chatwithchat.presentation.common.descriptionRes

@Composable
fun SetupPlatformTypeScreen(
    modifier: Modifier = Modifier,
    setupViewModel: SetupViewModelV2 = hiltViewModel(),
    onPlatformTypeSelected: () -> Unit,
    onBackAction: () -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SetupAppBar(onBackAction) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Header
            PlatformTypeHeader()

            // Platform type list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(ApiProtocol.selectable) { protocol ->
                    PlatformTypeCard(
                        protocol = protocol,
                        onClick = {
                            setupViewModel.selectProtocol(protocol)
                            onPlatformTypeSelected()
                        }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PlatformTypeHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier
                    .padding(4.dp)
                    .weight(1f, fill = false)
                    .semantics { heading() },
                text = stringResource(R.string.choose_platform_type),
                style = MaterialTheme.typography.headlineMedium
            )
            SettingsHelpTooltipIcon(helpText = stringResource(R.string.choose_platform_type_description))
        }
    }
}

@Composable
private fun PlatformTypeCard(
    modifier: Modifier = Modifier,
    protocol: ApiProtocol,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The protocol explanation sits behind the help icon instead of a subtitle on every card.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = protocol.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                SettingsHelpTooltipIcon(helpText = stringResource(protocol.descriptionRes()))
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Arrow icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
