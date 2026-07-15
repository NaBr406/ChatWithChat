package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.runtime.saveable.listSaver

internal data class ChatScrollAnchor(
    val turnKey: String,
    val offset: Int
)

internal sealed interface ChatScrollIntent {
    data object FollowingLatest : ChatScrollIntent

    data class ReadingHistory(
        val anchor: ChatScrollAnchor
    ) : ChatScrollIntent
}

internal val chatScrollIntentSaver = listSaver<ChatScrollIntent, Any>(
    save = { intent -> intent.toSavedValues() },
    restore = ::chatScrollIntentFromSavedValues
)

internal fun ChatScrollIntent.toSavedValues(): List<Any> = when (this) {
    ChatScrollIntent.FollowingLatest -> listOf(SAVED_FOLLOWING_LATEST)
    is ChatScrollIntent.ReadingHistory -> listOf(
        SAVED_READING_HISTORY,
        anchor.turnKey,
        anchor.offset
    )
}

internal fun chatScrollIntentFromSavedValues(values: List<Any>): ChatScrollIntent? = when (values.firstOrNull()) {
    SAVED_FOLLOWING_LATEST -> ChatScrollIntent.FollowingLatest
    SAVED_READING_HISTORY -> {
        val turnKey = values.getOrNull(1) as? String
        val offset = values.getOrNull(2) as? Int
        if (turnKey == null || offset == null) {
            null
        } else {
            ChatScrollIntent.ReadingHistory(ChatScrollAnchor(turnKey, offset))
        }
    }
    else -> null
}

internal sealed interface ChatScrollEvent {
    data class UserScrolled(
        val canScrollForward: Boolean,
        val anchor: ChatScrollAnchor?
    ) : ChatScrollEvent

    data object FollowLatestRequested : ChatScrollEvent

    data object StreamCompleted : ChatScrollEvent

    data object ViewportChanged : ChatScrollEvent
}

internal fun reduceChatScrollIntent(
    current: ChatScrollIntent,
    event: ChatScrollEvent
): ChatScrollIntent = when (event) {
    ChatScrollEvent.FollowLatestRequested -> ChatScrollIntent.FollowingLatest
    ChatScrollEvent.StreamCompleted -> current
    ChatScrollEvent.ViewportChanged -> current
    is ChatScrollEvent.UserScrolled -> when {
        !event.canScrollForward -> ChatScrollIntent.FollowingLatest
        event.anchor != null -> ChatScrollIntent.ReadingHistory(event.anchor)
        else -> current
    }
}

internal fun ChatScrollIntent.shouldFollowStreaming(isStreaming: Boolean): Boolean =
    isStreaming && this == ChatScrollIntent.FollowingLatest

internal fun ChatScrollIntent.shouldRestoreFollowingViewport(
    isScrollInProgress: Boolean,
    canScrollForward: Boolean,
    isProgrammaticScrollInProgress: Boolean
): Boolean =
    this == ChatScrollIntent.FollowingLatest &&
        !isScrollInProgress &&
        canScrollForward &&
        !isProgrammaticScrollInProgress

internal data class ChatTurnIdentity(
    val persistedMessageId: Int
)

internal class ChatTurnKeyRegistry {
    private data class Entry(
        val identity: ChatTurnIdentity,
        val key: String
    )

    private var entries: List<Entry> = emptyList()
    private var nextTemporaryKey = 0L

    fun update(turns: List<ChatTurnIdentity>): List<String> {
        val previousEntries = entries
        val usedPreviousEntries = BooleanArray(previousEntries.size)
        val nextEntries = MutableList<Entry?>(turns.size) { null }

        turns.forEachIndexed { index, identity ->
            if (identity.persistedMessageId <= 0) return@forEachIndexed
            val previousIndex = previousEntries.indices.firstOrNull { previousIndex ->
                !usedPreviousEntries[previousIndex] &&
                    previousEntries[previousIndex].identity.persistedMessageId == identity.persistedMessageId
            }
            if (previousIndex != null) {
                usedPreviousEntries[previousIndex] = true
                nextEntries[index] = Entry(identity, previousEntries[previousIndex].key)
            }
        }

        turns.forEachIndexed { index, identity ->
            if (nextEntries[index] != null) return@forEachIndexed
            val previousEntry = previousEntries.getOrNull(index)
            if (
                previousEntry != null &&
                !usedPreviousEntries[index] &&
                (
                    identity.persistedMessageId <= 0 ||
                        previousEntry.identity.persistedMessageId <= 0
                    )
            ) {
                usedPreviousEntries[index] = true
                nextEntries[index] = Entry(identity, previousEntry.key)
            }
        }

        turns.forEachIndexed { index, identity ->
            if (nextEntries[index] != null) return@forEachIndexed
            val key = if (identity.persistedMessageId > 0) {
                "chat-turn-message-${identity.persistedMessageId}"
            } else {
                "chat-turn-temporary-${nextTemporaryKey++}"
            }
            nextEntries[index] = Entry(identity, key)
        }

        entries = nextEntries.map { checkNotNull(it) }
        return entries.map(Entry::key)
    }
}

private const val SAVED_FOLLOWING_LATEST = "following_latest"
private const val SAVED_READING_HISTORY = "reading_history"
