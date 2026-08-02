package cn.nabr.chatwithchat.presentation.ui.setup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupAppBar(
    backAction: (() -> Unit)? = null
) {
    val materialColors = settingsMaterialColors()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = materialColors.navigation,
            titleContentColor = materialColors.primaryLabel,
            navigationIconContentColor = materialColors.primaryLabel
        ),
        title = { },
        navigationIcon = {
            backAction?.let { action ->
                IconButton(onClick = action) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                }
            }
        }
    )
}
