package cn.nabr.chatwithchat.data.model

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class ChatAttachment(
    val localFilePath: String,
    val preparedFilePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val wasResized: Boolean = false,
    val providerRefs: List<AttachmentProviderRef> = emptyList()
) {
    val filePathForDisplay: String = preparedFilePath.ifBlank { localFilePath }
    val resolvedDisplayName: String = displayName.ifBlank { File(filePathForDisplay).name }

    fun providerRefFor(platformUid: String): AttachmentProviderRef? = providerRefs.firstOrNull { it.platformUid == platformUid }

    fun upsertProviderRef(providerRef: AttachmentProviderRef): ChatAttachment = copy(
        providerRefs = providerRefs
            .filterNot { it.platformUid == providerRef.platformUid }
            .plus(providerRef)
    )

    fun clearProviderRef(platformUid: String): ChatAttachment = copy(
        providerRefs = providerRefs.filterNot { it.platformUid == platformUid }
    )
}

@Serializable
data class AttachmentProviderRef(
    val platformUid: String,
    val remoteType: AttachmentRemoteType,
    val remoteId: String,
    val remoteName: String? = null,
    val mimeType: String,
    val uploadedAt: Long
)

@Serializable
enum class AttachmentRemoteType {
    OPENAI_FILE,
    ANTHROPIC_FILE,
    GOOGLE_FILE
}
