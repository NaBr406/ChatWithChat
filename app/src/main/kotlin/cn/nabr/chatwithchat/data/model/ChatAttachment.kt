package cn.nabr.chatwithchat.data.model

import cn.nabr.chatwithchat.util.FileUtils
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
    val providerRefs: List<AttachmentProviderRef> = emptyList(),
    /** 文字类附件在本地抽取出的正文长度。正文本身不落库，只留长度用于 token 估算与 UI 提示。 */
    val documentCharCount: Int? = null,
    val documentTruncated: Boolean = false
) {
    val filePathForDisplay: String = preparedFilePath.ifBlank { localFilePath }
    val resolvedDisplayName: String = displayName.ifBlank { File(filePathForDisplay).name }

    /** 只读派生属性（无 backing field），不会被序列化进 Room 的 JSON 列。 */
    val isImage: Boolean
        get() = FileUtils.isInlineImageMimeType(mimeType)

    val isTextDocument: Boolean
        get() = FileUtils.isTextDocumentMimeType(mimeType)

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
