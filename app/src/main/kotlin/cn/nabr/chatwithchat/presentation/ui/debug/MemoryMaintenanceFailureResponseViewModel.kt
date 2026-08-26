package cn.nabr.chatwithchat.presentation.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.nabr.chatwithchat.data.database.dao.MemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceFailureResponse
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceFailureResponseStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MemoryMaintenanceFailureResponseViewModel @Inject constructor(
    private val failureResponseStore: MemoryMaintenanceFailureResponseStore,
    private val maintenanceJobDao: MemoryMaintenanceJobDao
) : ViewModel() {
    private val _responses = MutableStateFlow<List<MemoryMaintenanceFailureResponse>>(emptyList())
    val responses = _responses.asStateFlow()

    private val _jobs = MutableStateFlow<List<MemoryMaintenanceJob>>(emptyList())
    val jobs = _jobs.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _responses.value = failureResponseStore.readResponses()
            _jobs.value = maintenanceJobDao.getVisibleJobs()
        }
    }
}
