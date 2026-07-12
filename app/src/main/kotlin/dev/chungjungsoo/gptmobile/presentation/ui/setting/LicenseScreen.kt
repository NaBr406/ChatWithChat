package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.SettingsMaterialGroup
import dev.chungjungsoo.gptmobile.presentation.common.SettingsTopAppBar
import dev.chungjungsoo.gptmobile.presentation.common.settingsMaterialColors

@Composable
fun LicenseScreen(
    onNavigationClick: () -> Unit
) {
    val libraries = produceLibraries(R.raw.aboutlibraries)
    val materialColors = settingsMaterialColors()

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.license),
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = materialColors.canvas
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            SettingsMaterialGroup(modifier = Modifier.fillMaxSize()) {
                LibrariesContainer(libraries.value, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
