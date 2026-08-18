package cn.nabr.chatwithchat.presentation.ui.setting

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.SessionTextSkillBinding
import cn.nabr.chatwithchat.data.repository.ChatRepository
import cn.nabr.chatwithchat.data.textskill.InstalledSkillPackage
import cn.nabr.chatwithchat.data.textskill.RegisteredTextSkillVariant
import cn.nabr.chatwithchat.data.textskill.TextSkillContentPreview
import cn.nabr.chatwithchat.data.textskill.TextSkillInstallPreview
import cn.nabr.chatwithchat.data.textskill.TextSkillPackageRepository
import cn.nabr.chatwithchat.data.textskill.TextSkillVariantKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SkillsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageRepository: TextSkillPackageRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {
    data class ImportPreviewState(
        val preview: TextSkillInstallPreview,
        val affectedBindingCount: Int
    )

    data class DeletePreviewState(
        val packageItem: InstalledSkillPackage,
        val affectedBindingCount: Int
    )

    data class ContentPreviewState(
        val variant: RegisteredTextSkillVariant,
        val content: TextSkillContentPreview
    )

    private val _packages = MutableStateFlow<List<InstalledSkillPackage>>(emptyList())
    val packages = _packages.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _importPreview = MutableStateFlow<ImportPreviewState?>(null)
    val importPreview = _importPreview.asStateFlow()
    private val _deletePreview = MutableStateFlow<DeletePreviewState?>(null)
    val deletePreview = _deletePreview.asStateFlow()
    private val _contentPreview = MutableStateFlow<ContentPreviewState?>(null)
    val contentPreview = _contentPreview.asStateFlow()

    init {
        viewModelScope.launch {
            packageRepository.packages.collect { installed -> _packages.value = installed }
        }
    }

    fun requestImport(uri: Uri) {
        if (_isImporting.value) return
        viewModelScope.launch {
            _isImporting.value = true
            runCatching { packageRepository.prepareInstall(uri) }
                .onSuccess { preview ->
                    _importPreview.value = ImportPreviewState(
                        preview = preview,
                        affectedBindingCount = preview.replacedPackage
                            ?.let { packageItem -> chatRepository.textSkillBindingCount(packageItem.packageId) }
                            ?: 0
                    )
                }
                .onFailure { error ->
                    _message.value = error.message ?: context.getString(R.string.skill_import_failed)
                }
            _isImporting.value = false
        }
    }

    fun confirmImport() {
        val state = _importPreview.value ?: return
        viewModelScope.launch {
            _isImporting.value = true
            val shouldPauseBindings =
                state.preview.replacedPackage?.packageHash != state.preview.packageItem.packageHash
            val bindingsToRestore = if (shouldPauseBindings) {
                chatRepository.fetchSessionTextSkillBindingsForPackage(state.preview.packageItem.packageId)
                    .filter(SessionTextSkillBinding::isEnabled)
            } else {
                emptyList()
            }
            var bindingsPaused = false
            var installCompleted = false
            try {
                if (shouldPauseBindings) {
                    chatRepository.disableTextSkillBindingsForPackage(state.preview.packageItem.packageId)
                    bindingsPaused = true
                }
                val result = packageRepository.confirmInstall(state.preview.previewId)
                installCompleted = true
                _message.value = context.getString(R.string.skills_imported, result.packageItem.variants.size)
                _importPreview.value = null
            } catch (cancellation: CancellationException) {
                if (bindingsPaused && !installCompleted) restoreBindingsUncancellable(bindingsToRestore)
                throw cancellation
            } catch (error: Throwable) {
                // The enabled set is captured before pausing. A failed replacement must leave the
                // existing package and its session behavior unchanged.
                if (bindingsPaused && !installCompleted) restoreBindingsUncancellable(bindingsToRestore)
                _message.value = error.message ?: context.getString(R.string.skill_import_failed)
            }
            _isImporting.value = false
        }
    }

    fun cancelImport() {
        val previewId = _importPreview.value?.preview?.previewId ?: return
        _importPreview.value = null
        viewModelScope.launch {
            packageRepository.discardInstallPreview(previewId)
        }
    }

    fun requestDelete(packageItem: InstalledSkillPackage) {
        viewModelScope.launch {
            _deletePreview.value = DeletePreviewState(
                packageItem = packageItem,
                affectedBindingCount = chatRepository.textSkillBindingCount(packageItem.packageId)
            )
        }
    }

    fun confirmDelete() {
        val state = _deletePreview.value ?: return
        viewModelScope.launch {
            val bindingsToRestore = chatRepository
                .fetchSessionTextSkillBindingsForPackage(state.packageItem.packageId)
                .filter(SessionTextSkillBinding::isEnabled)
            var bindingsPaused = false
            var deleteCompleted = false
            try {
                chatRepository.disableTextSkillBindingsForPackage(state.packageItem.packageId)
                bindingsPaused = true
                packageRepository.delete(state.packageItem.packageId)
                deleteCompleted = true
                _message.value = if (state.affectedBindingCount > 0) {
                    context.getString(R.string.skill_deleted_with_bindings, state.affectedBindingCount)
                } else {
                    context.getString(R.string.skill_deleted)
                }
                _deletePreview.value = null
            } catch (cancellation: CancellationException) {
                if (bindingsPaused && !deleteCompleted) restoreBindingsUncancellable(bindingsToRestore)
                throw cancellation
            } catch (error: Throwable) {
                if (bindingsPaused && !deleteCompleted) restoreBindingsUncancellable(bindingsToRestore)
                _message.value = error.message ?: context.getString(R.string.skill_delete_failed)
            }
        }
    }

    fun cancelDelete() {
        _deletePreview.value = null
    }

    fun viewVariant(packageItem: InstalledSkillPackage, variant: RegisteredTextSkillVariant) {
        viewModelScope.launch {
            val key = TextSkillVariantKey(packageItem.packageId, variant.skillId, variant.variantKey)
            packageRepository.readContentPreview(key)
                ?.let { preview -> _contentPreview.value = ContentPreviewState(variant, preview) }
                ?: run { _message.value = context.getString(R.string.skill_content_unavailable) }
        }
    }

    fun closeContentPreview() {
        _contentPreview.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun restoreBindingsUncancellable(bindings: List<SessionTextSkillBinding>) {
        withContext(NonCancellable) {
            bindings.forEach { binding ->
                runCatching {
                    chatRepository.setSessionTextSkillBindingEnabled(
                        binding.chatId,
                        binding.packageId,
                        binding.skillId,
                        binding.variantKey,
                        true
                    )
                }
            }
        }
    }
}
