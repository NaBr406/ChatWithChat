package cn.nabr.chatwithchat.data.history

import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.util.isAssistantErrorMessage
import cn.nabr.chatwithchat.util.stripAssistantErrorNote
import java.security.MessageDigest

class ChatHistoryProjectionBuilder {
    fun buildForChat(
        chatRoom: ChatRoomV2,
        messages: List<MessageV2>,
        preferredPlatformUid: String? = null,
        stablePlatformOrder: List<String> = chatRoom.enabledPlatform
    ): List<ChatHistoryProjectionBuildResult> {
        val assistantsByUser = messages
            .asSequence()
            .filter { message -> message.platformType != null && message.linkedMessageId > 0 }
            .groupBy(MessageV2::linkedMessageId)

        return messages
            .asSequence()
            .filter { message -> message.platformType == null }
            .sortedBy(MessageV2::id)
            .map { user ->
                build(
                    chatRoom = chatRoom,
                    userMessage = user,
                    assistantMessages = assistantsByUser[user.id].orEmpty(),
                    preferredPlatformUid = preferredPlatformUid,
                    stablePlatformOrder = stablePlatformOrder
                )
            }
            .toList()
    }

    fun build(
        chatRoom: ChatRoomV2,
        userMessage: MessageV2,
        assistantMessages: List<MessageV2>,
        preferredPlatformUid: String? = null,
        stablePlatformOrder: List<String> = chatRoom.enabledPlatform
    ): ChatHistoryProjectionBuildResult {
        if (chatRoom.id <= 0 || userMessage.id <= 0 || userMessage.chatId != chatRoom.id) {
            return ChatHistoryProjectionBuildResult(skipCode = "invalid_source")
        }
        val userContent = userMessage.effectiveContent().trim().take(MAX_MESSAGE_CHARS)
        if (userContent.isBlank()) return ChatHistoryProjectionBuildResult(skipCode = "blank_user")

        val candidates = assistantMessages.mapNotNull { assistant ->
            val platformUid = assistant.platformType?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val content = stripAssistantErrorNote(assistant.effectiveContent()).trim().take(MAX_MESSAGE_CHARS)
            if (assistant.id <= 0 || content.isBlank() || isAssistantErrorMessage(content)) return@mapNotNull null
            AssistantCandidate(assistant, platformUid, content)
        }
        val canonical = candidates.firstOrNull { candidate -> candidate.platformUid == preferredPlatformUid }
            ?: stablePlatformOrder.firstNotNullOfOrNull { platformUid ->
                candidates.firstOrNull { candidate -> candidate.platformUid == platformUid }
            }
            ?: candidates.minWithOrNull(compareBy(AssistantCandidate::platformUid, { candidate -> candidate.message.id }))
            ?: return ChatHistoryProjectionBuildResult(skipCode = "no_successful_assistant")

        val turnKey = "chat:${chatRoom.id}:user:${userMessage.id}"
        val title = chatRoom.title.trim().take(MAX_TITLE_CHARS)
        val contentHash = canonicalHash(
            projectionVersion = ChatHistoryContract.PROJECTION_VERSION,
            turnKey = turnKey,
            title = title,
            userMessageId = userMessage.id,
            userContent = userContent,
            assistantMessageId = canonical.message.id,
            assistantPlatformUid = canonical.platformUid,
            assistantContent = canonical.content
        )
        return ChatHistoryProjectionBuildResult(
            projection = ChatHistoryTurnProjection(
                turnKey = turnKey,
                chatId = chatRoom.id,
                userMessageId = userMessage.id,
                assistantMessageId = canonical.message.id,
                assistantPlatformUid = canonical.platformUid,
                title = title,
                userContent = userContent,
                assistantContent = canonical.content,
                searchTerms = ChatHistoryQueryNormalizer.searchColumn(title, userContent, canonical.content),
                contentHash = contentHash,
                projectionVersion = ChatHistoryContract.PROJECTION_VERSION,
                createdAt = minOf(userMessage.createdAt, canonical.message.createdAt),
                updatedAt = maxOf(chatRoom.updatedAt, userMessage.createdAt, canonical.message.createdAt)
            )
        )
    }

    private fun canonicalHash(
        projectionVersion: Int,
        turnKey: String,
        title: String,
        userMessageId: Int,
        userContent: String,
        assistantMessageId: Int,
        assistantPlatformUid: String,
        assistantContent: String
    ): String {
        val payload = buildString {
            appendFramed("projection_version", projectionVersion.toString())
            appendFramed("turn_key", turnKey)
            appendFramed("chat_title", title)
            appendFramed("user_message_id", userMessageId.toString())
            appendFramed("user_content", userContent)
            appendFramed("assistant_message_id", assistantMessageId.toString())
            appendFramed("assistant_platform_uid", assistantPlatformUid)
            appendFramed("assistant_content", assistantContent)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendFramed(fieldName: String, value: String) {
        val field = "$fieldName=$value"
        append(field.toByteArray(Charsets.UTF_8).size)
        append(':')
        append(field)
        append('\n')
    }

    private data class AssistantCandidate(
        val message: MessageV2,
        val platformUid: String,
        val content: String
    )

    private companion object {
        const val MAX_TITLE_CHARS = 200
        const val MAX_MESSAGE_CHARS = 12_000
    }
}
