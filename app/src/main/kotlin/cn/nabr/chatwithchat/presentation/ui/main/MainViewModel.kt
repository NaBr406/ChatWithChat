package cn.nabr.chatwithchat.presentation.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MainLaunchState {
    data object Loading : MainLaunchState

    data class Resolved(val destination: MainLaunchDestination) : MainLaunchState
}

enum class MainLaunchDestination {
    Setup,
    Home,
    Migrate
}

@HiltViewModel
class MainViewModel @Inject constructor(private val settingRepository: SettingRepository) : ViewModel() {

    private val _launchState = MutableStateFlow<MainLaunchState>(initialMainLaunchState())
    val launchState: StateFlow<MainLaunchState> = _launchState.asStateFlow()

    init {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatforms()
            val platformV2s = settingRepository.fetchPlatformV2s()

            _launchState.value = MainLaunchState.Resolved(
                resolveLaunchDestination(
                    hasConfiguredLegacyPlatform = platforms.any { it.enabled || it.token != null },
                    hasV2Platforms = platformV2s.isNotEmpty()
                )
            )
        }
    }
}

internal fun initialMainLaunchState(): MainLaunchState = MainLaunchState.Loading

internal fun resolveLaunchDestination(
    hasConfiguredLegacyPlatform: Boolean,
    hasV2Platforms: Boolean
): MainLaunchDestination = when {
    hasV2Platforms -> MainLaunchDestination.Home
    hasConfiguredLegacyPlatform -> MainLaunchDestination.Migrate
    else -> MainLaunchDestination.Setup
}
