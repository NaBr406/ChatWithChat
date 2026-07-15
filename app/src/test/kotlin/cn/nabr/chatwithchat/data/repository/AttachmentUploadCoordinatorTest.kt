package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.context.ConversationTurn
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.anthropic.response.MessageResponseChunk
import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.google.response.GenerateContentResponse
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import cn.nabr.chatwithchat.data.model.AttachmentProviderRef
import cn.nabr.chatwithchat.data.model.AttachmentRemoteType
import cn.nabr.chatwithchat.data.model.ChatAttachment
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.network.AnthropicAPI
import cn.nabr.chatwithchat.data.network.GoogleAPI
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.ProviderFileUploadException
import cn.nabr.chatwithchat.data.network.UploadedProviderFile
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AttachmentUploadCoordinatorTest {
    @Test
    fun `openai file endpoint 404 falls back to inline attachment`() = runBlocking {
        val openAIAPI = FakeOpenAIAPI(
            uploadFailure = ProviderFileUploadException(
                providerName = "OpenAI",
                statusCode = 404,
                responseBody = "404 page not found"
            )
        )
        val coordinator = AttachmentUploadCoordinator(openAIAPI, FakeAnthropicAPI(), FakeGoogleAPI())
        val tempFile = File.createTempFile("attachment", ".png").apply {
            writeBytes(ByteArray(32))
            deleteOnExit()
        }
        val message = MessageV2(
            content = "describe",
            platformType = null,
            attachments = listOf(
                ChatAttachment(
                    localFilePath = tempFile.absolutePath,
                    preparedFilePath = tempFile.absolutePath,
                    displayName = tempFile.name,
                    mimeType = "image/png",
                    sizeBytes = tempFile.length(),
                    providerRefs = listOf(
                        AttachmentProviderRef(
                            platformUid = "openai-platform",
                            remoteType = AttachmentRemoteType.OPENAI_FILE,
                            remoteId = "stale-file",
                            mimeType = "image/png",
                            uploadedAt = 1L
                        )
                    )
                )
            )
        )

        val updated = coordinator.ensureMessageAttachmentsForPlatform(
            message,
            PlatformV2(
                uid = "openai-platform",
                name = "OpenAI",
                compatibleType = ClientType.OPENAI,
                apiUrl = "https://proxy.example.com/",
                model = "gpt-4.1"
            )
        )

        assertEquals(1, openAIAPI.uploadCount)
        assertEquals(emptyList<AttachmentProviderRef>(), updated.attachments.single().providerRefs)
    }

    @Test
    fun `openai file upload non endpoint errors are preserved`() = runBlocking {
        val openAIAPI = FakeOpenAIAPI(
            uploadFailure = ProviderFileUploadException(
                providerName = "OpenAI",
                statusCode = 401,
                responseBody = """{"error":{"message":"invalid token"}}"""
            )
        )
        val coordinator = AttachmentUploadCoordinator(openAIAPI, FakeAnthropicAPI(), FakeGoogleAPI())
        val tempFile = File.createTempFile("attachment", ".png").apply {
            writeBytes(ByteArray(32))
            deleteOnExit()
        }

        try {
            coordinator.ensureMessageAttachmentsForPlatform(
                MessageV2(
                    content = "describe",
                    platformType = null,
                    attachments = listOf(
                        ChatAttachment(
                            localFilePath = tempFile.absolutePath,
                            preparedFilePath = tempFile.absolutePath,
                            displayName = tempFile.name,
                            mimeType = "image/png",
                            sizeBytes = tempFile.length()
                        )
                    )
                ),
                PlatformV2(
                    uid = "openai-platform",
                    name = "OpenAI",
                    compatibleType = ClientType.OPENAI,
                    apiUrl = "https://api.openai.com/",
                    model = "gpt-4.1"
                )
            )
            fail("Expected ProviderFileUploadException")
        } catch (e: ProviderFileUploadException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun `existing openai ref is reused without upload`() = runBlocking {
        val openAIAPI = FakeOpenAIAPI(isAvailable = true)
        val coordinator = AttachmentUploadCoordinator(openAIAPI, FakeAnthropicAPI(), FakeGoogleAPI())
        val message = MessageV2(
            content = "hello",
            platformType = null,
            attachments = listOf(
                ChatAttachment(
                    localFilePath = "/tmp/image.png",
                    preparedFilePath = "/tmp/image.png",
                    displayName = "image.png",
                    mimeType = "image/png",
                    sizeBytes = 1,
                    providerRefs = listOf(
                        AttachmentProviderRef(
                            platformUid = "openai-platform",
                            remoteType = AttachmentRemoteType.OPENAI_FILE,
                            remoteId = "file-existing",
                            mimeType = "image/png",
                            uploadedAt = 1L
                        )
                    )
                )
            )
        )

        val updated = coordinator.ensureMessageAttachmentsForPlatform(
            message,
            PlatformV2(
                uid = "openai-platform",
                name = "OpenAI",
                compatibleType = ClientType.OPENAI,
                apiUrl = "https://api.openai.com",
                model = "gpt-4.1"
            )
        )

        assertEquals(0, openAIAPI.uploadCount)
        assertEquals("file-existing", updated.attachments.single().providerRefs.single().remoteId)
    }

    @Test
    fun `missing google ref uploads and stores remote uri`() = runBlocking {
        val googleAPI = FakeGoogleAPI()
        val coordinator = AttachmentUploadCoordinator(FakeOpenAIAPI(), FakeAnthropicAPI(), googleAPI)
        val tempFile = File.createTempFile("attachment", ".png").apply {
            writeBytes(ByteArray(32))
            deleteOnExit()
        }
        val message = MessageV2(
            content = "describe",
            platformType = null,
            attachments = listOf(
                ChatAttachment(
                    localFilePath = tempFile.absolutePath,
                    preparedFilePath = tempFile.absolutePath,
                    displayName = tempFile.name,
                    mimeType = "image/png",
                    sizeBytes = tempFile.length()
                )
            )
        )

        val updated = coordinator.ensureMessageAttachmentsForPlatform(
            message,
            PlatformV2(
                uid = "google-platform",
                name = "Google",
                compatibleType = ClientType.GOOGLE,
                apiUrl = "https://generativelanguage.googleapis.com",
                model = "gemini-2.0-flash"
            )
        )

        assertEquals(1, googleAPI.uploadCount)
        assertEquals("google-uri", updated.attachments.single().providerRefs.single().remoteId)
        assertEquals("files/google-file", updated.attachments.single().providerRefs.single().remoteName)
    }

    @Test
    fun `inline attachment budget rejects oversized payloads`() = runBlocking {
        val coordinator = AttachmentUploadCoordinator(FakeOpenAIAPI(), FakeAnthropicAPI(), FakeGoogleAPI())
        val first = File.createTempFile("inline-first", ".png").apply {
            writeBytes(ByteArray(7 * 1024 * 1024))
            deleteOnExit()
        }
        val second = File.createTempFile("inline-second", ".png").apply {
            writeBytes(ByteArray(7 * 1024 * 1024))
            deleteOnExit()
        }

        try {
            coordinator.validateInlineAttachmentBudget(
                contextTurns = listOf(
                    ConversationTurn(
                        userMessage = MessageV2(
                            content = "hi",
                            platformType = null,
                            attachments = listOf(
                                ChatAttachment(
                                    localFilePath = first.absolutePath,
                                    preparedFilePath = first.absolutePath,
                                    displayName = first.name,
                                    mimeType = "image/png",
                                    sizeBytes = first.length()
                                ),
                                ChatAttachment(
                                    localFilePath = second.absolutePath,
                                    preparedFilePath = second.absolutePath,
                                    displayName = second.name,
                                    mimeType = "image/png",
                                    sizeBytes = second.length()
                                )
                            )
                        ),
                        assistantMessage = null,
                        isCurrentTurn = true
                    )
                )
            )
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    private class FakeOpenAIAPI(
        private val isAvailable: Boolean = false,
        private val uploadFailure: ProviderFileUploadException? = null
    ) : OpenAIAPI {
        var uploadCount = 0

        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamChatCompletion(request: ChatCompletionRequest, timeoutSeconds: Int): Flow<ChatCompletionChunk> = emptyFlow()

        override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int): Flow<ResponsesStreamEvent> = emptyFlow()

        override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile {
            uploadCount += 1
            uploadFailure?.let { throw it }
            return UploadedProviderFile(id = "file-uploaded", mimeType = mimeType)
        }

        override suspend fun isFileAvailable(fileId: String): Boolean = isAvailable
    }

    private class FakeAnthropicAPI : AnthropicAPI {
        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamChatMessage(messageRequest: MessageRequest, timeoutSeconds: Int): Flow<MessageResponseChunk> = emptyFlow()

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String
        ): UploadedProviderFile = UploadedProviderFile(id = "anthropic-file", mimeType = mimeType)

        override suspend fun isFileAvailable(fileId: String): Boolean = false
    }

    private class FakeGoogleAPI : GoogleAPI {
        var uploadCount = 0

        override fun setToken(token: String?) = Unit

        override fun setAPIUrl(url: String) = Unit

        override fun streamGenerateContent(
            request: GenerateContentRequest,
            model: String,
            timeoutSeconds: Int
        ): Flow<GenerateContentResponse> = emptyFlow()

        override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String): UploadedProviderFile {
            uploadCount += 1
            return UploadedProviderFile(
                id = "google-id",
                name = "files/google-file",
                uri = "google-uri",
                mimeType = mimeType
            )
        }

        override suspend fun isFileAvailable(fileName: String): Boolean = false
    }
}
