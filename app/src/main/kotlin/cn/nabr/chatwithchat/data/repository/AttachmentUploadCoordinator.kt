package cn.nabr.chatwithchat.data.repository

import android.content.Context
import cn.nabr.chatwithchat.data.attachment.DocumentTextExtractor
import cn.nabr.chatwithchat.data.context.ConversationTurn
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.AttachmentProviderRef
import cn.nabr.chatwithchat.data.model.AttachmentRemoteType
import cn.nabr.chatwithchat.data.model.ChatAttachment
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.ProviderFileUploadException
import cn.nabr.chatwithchat.data.network.providerEndpoint
import cn.nabr.chatwithchat.util.AttachmentNaming
import cn.nabr.chatwithchat.util.FileUtils
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AttachmentUploadCoordinator @Inject constructor(
    private val openAIAPI: OpenAIAPI,
    private val anthropicAPI: AnthropicAPI,
    private val googleAPI: GoogleAPI
) {
    suspend fun prepareLocalAttachment(context: Context, filePath: String): ChatAttachment? = withContext(Dispatchers.IO) {
        val preparationResult = FileUtils.prepareAttachmentForUpload(context, filePath) ?: return@withContext null
        val preparedFilePath = preparationResult.preparedFilePath
        val isDocument = FileUtils.isTextDocumentMimeType(preparationResult.mimeType)
        // 文字类附件在这里就先抽一次正文：抽不出东西（扫描版 PDF、加密文件、二进制乱码）
        // 就让草稿直接失败，别等到发送时才发现要塞一个空的 <attachment>。
        val documentText = if (isDocument) {
            DocumentTextExtractor.extract(context, preparedFilePath, preparationResult.mimeType)
                ?: return@withContext null
        } else {
            null
        }
        val dimensions = if (isDocument) null else FileUtils.getImageDimensionsForDisplay(context, preparedFilePath)
        ChatAttachment(
            localFilePath = filePath,
            preparedFilePath = preparedFilePath,
            displayName = AttachmentNaming.attachmentDisplayName(File(preparedFilePath).name),
            mimeType = preparationResult.mimeType,
            sizeBytes = FileUtils.getFileSize(context, preparedFilePath),
            width = dimensions?.first,
            height = dimensions?.second,
            wasResized = preparationResult.wasResized,
            documentCharCount = documentText?.charCount,
            documentTruncated = documentText?.truncated == true
        )
    }

    suspend fun ensureMessageAttachmentsForPlatform(message: MessageV2, platform: PlatformV2): MessageV2 {
        if (message.attachments.isEmpty()) return message
        // 只有图片才走 provider 的文件上传：把 docx/PDF POST 到 files 端点会拿回一个
        // 谁也不认的 file id，然后拼出非法的 image/file content block。文档靠内联文本投递。
        val updatedAttachments = when (platform.compatibleType) {
            ClientType.OPENAI -> message.attachments.map { attachment ->
                if (isImageAttachment(attachment)) ensureOpenAIRef(attachment, platform) else attachment
            }

            ClientType.ANTHROPIC -> message.attachments.map { attachment ->
                if (isImageAttachment(attachment)) ensureAnthropicRef(attachment, platform) else attachment
            }

            ClientType.GOOGLE -> message.attachments.map { attachment ->
                if (isImageAttachment(attachment)) ensureGoogleRef(attachment, platform) else attachment
            }

            else -> message.attachments
        }
        return if (updatedAttachments == message.attachments) message else message.copy(attachments = updatedAttachments)
    }

    suspend fun validateInlineAttachmentBudget(
        contextTurns: List<ConversationTurn>,
        maxInlineBytes: Long = MAX_SAFE_INLINE_BYTES
    ) {
        val totalPreparedBytes = contextTurns
            .flatMap { turn ->
                buildList {
                    addAll(turn.userMessage.attachments)
                    turn.assistantMessage?.let { addAll(it.attachments) }
                }
            }
            // 只算图片：文档不进 inline 字节，否则一个 30 MB 的 PDF 会在 Groq/Ollama 上误报"图片太大"
            .filter { isImageAttachment(it) }
            .sumOf { attachment ->
                val file = File(resolveUploadFilePath(attachment))
                when {
                    file.exists() -> file.length()
                    attachment.sizeBytes > 0L -> attachment.sizeBytes
                    else -> 0L
                }
            }

        if (totalPreparedBytes > maxInlineBytes) {
            throw IllegalStateException(
                "这些图片太大，无法安全上传到当前提供商。请移除部分图片，或改用 OpenAI、Anthropic 或 Google。"
            )
        }
    }

    private suspend fun ensureOpenAIRef(attachment: ChatAttachment, platform: PlatformV2): ChatAttachment {
        val platformUid = platform.uid
        val endpoint = platform.providerEndpoint()
        val existingRef = attachment.providerRefFor(platformUid)
        if (existingRef?.remoteType == AttachmentRemoteType.OPENAI_FILE && openAIAPI.isFileAvailable(existingRef.remoteId, endpoint)) {
            return attachment
        }

        val uploadFile = try {
            openAIAPI.uploadFile(
                filePath = resolveUploadFilePath(attachment),
                fileName = attachment.resolvedDisplayName,
                mimeType = resolveMimeType(attachment),
                endpoint = endpoint
            )
        } catch (e: ProviderFileUploadException) {
            if (e.isEndpointUnavailable) {
                return attachment.clearProviderRef(platformUid)
            }
            throw e
        }
        return attachment.upsertProviderRef(
            AttachmentProviderRef(
                platformUid = platformUid,
                remoteType = AttachmentRemoteType.OPENAI_FILE,
                remoteId = uploadFile.id,
                mimeType = uploadFile.mimeType,
                uploadedAt = System.currentTimeMillis() / 1000
            )
        )
    }

    private suspend fun ensureAnthropicRef(attachment: ChatAttachment, platform: PlatformV2): ChatAttachment {
        val platformUid = platform.uid
        val endpoint = platform.providerEndpoint()
        val existingRef = attachment.providerRefFor(platformUid)
        if (existingRef?.remoteType == AttachmentRemoteType.ANTHROPIC_FILE && anthropicAPI.isFileAvailable(existingRef.remoteId, endpoint)) {
            return attachment
        }

        val uploadFile = anthropicAPI.uploadFile(
            filePath = resolveUploadFilePath(attachment),
            fileName = attachment.resolvedDisplayName,
            mimeType = resolveMimeType(attachment),
            endpoint = endpoint
        )
        return attachment.upsertProviderRef(
            AttachmentProviderRef(
                platformUid = platformUid,
                remoteType = AttachmentRemoteType.ANTHROPIC_FILE,
                remoteId = uploadFile.id,
                mimeType = uploadFile.mimeType,
                uploadedAt = System.currentTimeMillis() / 1000
            )
        )
    }

    private suspend fun ensureGoogleRef(attachment: ChatAttachment, platform: PlatformV2): ChatAttachment {
        val platformUid = platform.uid
        val endpoint = platform.providerEndpoint()
        val existingRef = attachment.providerRefFor(platformUid)
        if (
            existingRef?.remoteType == AttachmentRemoteType.GOOGLE_FILE &&
            !existingRef.remoteName.isNullOrBlank() &&
            googleAPI.isFileAvailable(existingRef.remoteName, endpoint)
        ) {
            return attachment
        }

        val uploadFile = googleAPI.uploadFile(
            filePath = resolveUploadFilePath(attachment),
            fileName = attachment.resolvedDisplayName,
            mimeType = resolveMimeType(attachment),
            endpoint = endpoint
        )
        return attachment.upsertProviderRef(
            AttachmentProviderRef(
                platformUid = platformUid,
                remoteType = AttachmentRemoteType.GOOGLE_FILE,
                remoteId = uploadFile.uri ?: uploadFile.id,
                remoteName = uploadFile.name,
                mimeType = uploadFile.mimeType,
                uploadedAt = System.currentTimeMillis() / 1000
            )
        )
    }

    private fun resolveUploadFilePath(attachment: ChatAttachment): String = attachment.preparedFilePath.ifBlank { attachment.localFilePath }

    private fun resolveMimeType(attachment: ChatAttachment): String = attachment.mimeType.ifBlank {
        FileUtils.getMimeTypeFromPath(resolveUploadFilePath(attachment))
    }

    private fun isImageAttachment(attachment: ChatAttachment): Boolean = FileUtils.isInlineImageMimeType(resolveMimeType(attachment))

    companion object {
        const val MAX_SAFE_INLINE_BYTES = 12L * 1024 * 1024
    }
}
