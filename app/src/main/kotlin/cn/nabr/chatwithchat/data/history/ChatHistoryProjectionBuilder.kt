package cn.nabr.chatwithchat.data.history

import cn.nabr.chatwithchat.data.context.semanticAssistantContent
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.util.isAssistantErrorMessage
import cn.nabr.chatwithchat.util.stripAssistantErrorNote
import java.security.MessageDigest

class ChatHistoryProjectionBuilder(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    fun buildAll(
        chatRoom: ChatRoomV2,
        messages: List<MessageV2>,
        preferredPlatformUid: String? = chatRoom.enabledPlatform.firstOrNull(),
        stablePlatformOrder: List<String> = chatRoom.enabledPlatform
    ): List<ChatHistoryProjectionBuildResult> {
        val orderedMessages = messages.sortedWith(compareBy<MessageV2> { it.createdAt }.thenBy { it.id })
        return orderedMessages
            .filter { it.platformType == null }
            .map { user -> buildForUser(chatRoom, orderedMessages, user, preferredPlatformUid, stablePlatformOrder) }
    }

    fun build(
        chatRoom: ChatRoomV2,
        messages: List<MessageV2>,
        preferredPlatformUid: String? = chatRoom.enabledPlatform.firstOrNull(),
        stablePlatformOrder: List<String> = chatRoom.enabledPlatform
    ): ChatHistoryProjectionBuildResult {
        val orderedMessages = messages.sortedWith(compareBy<MessageV2> { it.createdAt }.thenBy { it.id })
        val user = orderedMessages.lastOrNull { it.platformType == null }
            ?: return ChatHistoryProjectionBuildResult(null, "blank_user")
        return buildForUser(chatRoom, orderedMessages, user, preferredPlatformUid, stablePlatformOrder)
    }

    private fun buildForUser(
        chatRoom: ChatRoomV2,
        orderedMessages: List<MessageV2>,
        userMessage: MessageV2,
        preferredPlatformUid: String?,
        stablePlatformOrder: List<String>
    ): ChatHistoryProjectionBuildResult {
        if (chatRoom.id <= 0) return ChatHistoryProjectionBuildResult(null, "invalid_chat")
        if (userMessage.id <= 0 || userMessage.effectiveContent().trim().isBlank()) {
            return ChatHistoryProjectionBuildResult(null, "blank_user")
        }
        val userIndex = orderedMessages.indexOf(userMessage)
        val assistants = orderedMessages
            .drop(userIndex + 1)
            .takeWhile { it.platformType != null }
            .filter { it.id > 0 }
        val successful = assistants.mapNotNull { assistant ->
            val content = stripAssistantErrorNote(assistant.semanticAssistantContent()).trim()
            if (content.isBlank() || isAssistantErrorMessage(content)) return@mapNotNull null
            assistant.platformType?.let { platformUid -> assistant to platformUid }
        }
        val canonical = successful.firstOrNull { (_, platformUid) -> platformUid == preferredPlatformUid }
            ?: stablePlatformOrder.firstNotNullOfOrNull { platformUid ->
                successful.firstOrNull { (_, candidateUid) -> candidateUid == platformUid }
            }
            ?: successful.minByOrNull { (_, platformUid) -> platformUid }
            ?: return ChatHistoryProjectionBuildResult(null, "no_successful_assistant")

        val assistant = canonical.first
        val assistantUid = canonical.second.trim()
        if (assistantUid.isBlank()) return ChatHistoryProjectionBuildResult(null, "missing_platform")
        val title = chatRoom.title.normalizeHistoryText(MAX_TITLE_CHARS)
        val userContent = userMessage.effectiveContent().normalizeHistoryText(MAX_MESSAGE_CHARS)
        val assistantContent = stripAssistantErrorNote(assistant.semanticAssistantContent())
            .normalizeHistoryText(MAX_MESSAGE_CHARS)
        if (userContent.isBlank() || assistantContent.isBlank()) {
            return ChatHistoryProjectionBuildResult(null, "blank_projection")
        }
        val attachmentMetadata = userMessage.attachments.joinToString(" ") { attachment ->
            "${attachment.resolvedDisplayName} ${attachment.mimeType}"
        }
        val searchTerms = ChatHistoryQueryNormalizer.indexTerms(
            "$title $userContent $assistantContent $attachmentMetadata"
        ).joinToString(" ")
        val turnKey = "chat:${chatRoom.id}:user:${userMessage.id}"
        val hash = ChatHistoryProjectionHasher.sha256(
            projectionVersion = CURRENT_PROJECTION_VERSION,
            turnKey = turnKey,
            title = title,
            userMessageId = userMessage.id,
            userContent = userContent,
            assistantMessageId = assistant.id,
            assistantPlatformUid = assistantUid,
            assistantContent = assistantContent
        )
        val now = clock()
        return ChatHistoryProjectionBuildResult(
            projection = ChatHistoryProjection(
                turnKey = turnKey,
                chatId = chatRoom.id,
                userMessageId = userMessage.id,
                assistantMessageId = assistant.id,
                assistantPlatformUid = assistantUid,
                title = title,
                userContent = userContent,
                assistantContent = assistantContent,
                searchTerms = searchTerms,
                contentHash = hash,
                createdAt = userMessage.createdAt,
                updatedAt = now
            )
        )
    }

    private fun String.normalizeHistoryText(maxLength: Int): String =
        replace(Regex("\\s+"), " ").trim().take(maxLength)

    private companion object {
        const val MAX_TITLE_CHARS = 200
        const val MAX_MESSAGE_CHARS = 12_000
    }
}

object ChatHistoryProjectionHasher {
    fun sha256(
        projectionVersion: Int,
        turnKey: String,
        title: String,
        userMessageId: Int,
        userContent: String,
        assistantMessageId: Int,
        assistantPlatformUid: String,
        assistantContent: String
    ): String {
        val fields = listOf(
            "projection_version" to projectionVersion.toString(),
            "turn_key" to turnKey,
            "title" to title,
            "user_message_id" to userMessageId.toString(),
            "user_content" to userContent,
            "assistant_message_id" to assistantMessageId.toString(),
            "assistant_platform_uid" to assistantPlatformUid,
            "assistant_content" to assistantContent
        )
        val payload = buildString {
            fields.forEach { (name, value) ->
                val framed = "$name=$value"
                append(framed.toByteArray(Charsets.UTF_8).size)
                append(':')
                append(framed)
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
