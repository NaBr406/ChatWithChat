package cn.nabr.chatwithchat.presentation.ui.debug

import androidx.lifecycle.ViewModel
import cn.nabr.chatwithchat.data.debug.PromptTraceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PromptTraceViewModel @Inject constructor(
    private val promptTraceStore: PromptTraceStore
) : ViewModel() {
    val entries = promptTraceStore.entries

    fun clear() {
        promptTraceStore.clear()
    }
}
