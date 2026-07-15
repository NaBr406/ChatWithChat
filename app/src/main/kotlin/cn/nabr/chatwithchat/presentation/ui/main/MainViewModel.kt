package cn.nabr.chatwithchat.presentation.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import cn.nabr.chatwithchat.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(private val settingRepository: SettingRepository) : ViewModel() {

    sealed class SplashEvent {
        data object OpenSetup : SplashEvent()
        data object OpenHome : SplashEvent()
        data object OpenMigrate : SplashEvent()
    }

    private val _isReady: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _event = Channel<SplashEvent>(capacity = Channel.CONFLATED)
    val event = _event.receiveAsFlow()

    init {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatforms()
            val platformV2s = settingRepository.fetchPlatformV2s()

            sendSplashEvent(
                resolveSplashEvent(
                    hasConfiguredLegacyPlatform = platforms.any { it.enabled || it.token != null },
                    hasV2Platforms = platformV2s.isNotEmpty()
                )
            )

            setAsReady()
        }
    }

    private suspend fun sendSplashEvent(event: SplashEvent) {
        _event.send(event)
    }

    private fun setAsReady() {
        _isReady.update { true }
    }
}

internal fun resolveSplashEvent(
    hasConfiguredLegacyPlatform: Boolean,
    hasV2Platforms: Boolean
): MainViewModel.SplashEvent = when {
    hasV2Platforms -> MainViewModel.SplashEvent.OpenHome
    hasConfiguredLegacyPlatform -> MainViewModel.SplashEvent.OpenMigrate
    else -> MainViewModel.SplashEvent.OpenSetup
}
