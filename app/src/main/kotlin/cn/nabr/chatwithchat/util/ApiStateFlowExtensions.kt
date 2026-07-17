package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.data.database.entity.AssistantRevision
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.resetActiveRevision
import cn.nabr.chatwithchat.data.database.entity.safeDedupeKey
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import cn.nabr.chatwithchat.presentation.ui.chat.ChatViewModel
import cn.nabr.chatwithchat.presentation.ui.chat.updateAssistantSlot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STREAM_PUBLISH_INTERVAL_MILLIS = 50L

suspend fun Flow<ApiState>.handleStates(
    messageFlow: MutableStateFlow<ChatViewModel.GroupedMessages>,
    turnIndex: Int,
    platformIdx: Int,
    onLoadingComplete: () -> Unit,
    currentTimeProvider: () -> Long = { System.currentTimeMillis() / 1000 },
    revisionToAppendOnSuccess: AssistantRevision? = null,
    onToolProgress: (ApiState) -> Unit = {}
) = coroutineScope {
    val buffer = StreamingMessageBuffer()
    val publishSignals = Channel<Unit>(capacity = Channel.CONFLATED)
    val publisher = launch {
        for (signal in publishSignals) {
            delay(STREAM_PUBLISH_INTERVAL_MILLIS)
            buffer.publishPending(messageFlow, turnIndex, platformIdx)
        }
    }
    var isCompletedSuccessfully = false
    var terminalError: String? = null

    try {
        collect { chunk ->
            when (chunk) {
                is ApiState.Thinking -> {
                    when (buffer.appendThought(chunk.thinkingChunk)) {
                        AppendResult.FIRST -> buffer.publishPending(messageFlow, turnIndex, platformIdx)
                        AppendResult.PENDING -> publishSignals.trySend(Unit)
                        AppendResult.EMPTY -> {}
                    }
                }

                is ApiState.Success -> {
                    when (buffer.appendContent(chunk.textChunk)) {
                        AppendResult.FIRST -> buffer.publishPending(messageFlow, turnIndex, platformIdx)
                        AppendResult.PENDING -> publishSignals.trySend(Unit)
                        AppendResult.EMPTY -> {}
                    }
                }

                is ApiState.SourcesUpdated -> {
                    messageFlow.setSourceMetadata(turnIndex, platformIdx, chunk.sources)
                }

                is ApiState.UsageUpdated -> {
                    messageFlow.setTokenUsage(turnIndex, platformIdx, chunk.usage)
                }

                ApiState.Done -> {
                    isCompletedSuccessfully = true
                }

                is ApiState.Error -> {
                    terminalError = chunk.message
                }

                is ApiState.ToolStarted,
                is ApiState.ToolFinished,
                is ApiState.ToolFailed -> {
                    onToolProgress(chunk)
                }

                else -> {}
            }
        }
    } finally {
        publishSignals.close()
        publisher.cancel()
        buffer.flush(messageFlow, turnIndex, platformIdx)
        val error = terminalError
        when {
            error != null -> messageFlow.setErrorMessage(
                turnIndex = turnIndex,
                platformIdx = platformIdx,
                error = error,
                currentTimeProvider = currentTimeProvider,
                revisionToAppend = revisionToAppendOnSuccess
            )

            isCompletedSuccessfully -> messageFlow.setTimestamp(
                turnIndex = turnIndex,
                platformIdx = platformIdx,
                currentTimeProvider = currentTimeProvider,
                revisionToAppend = revisionToAppendOnSuccess
            )
        }
        onLoadingComplete()
    }
}

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.setSourceMetadata(
    turnIndex: Int,
    platformIdx: Int,
    sources: List<MessageSourceMetadata>
) {
    val distinctSources = sources
        .mapNotNull { source -> source.safeDedupeKey()?.let { key -> key to source } }
        .distinctBy { (key, _) -> key }
        .map { (_, source) -> source }

    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            if (currentMessage.sourceMetadata == distinctSources) {
                currentMessage
            } else {
                currentMessage.copy(sourceMetadata = distinctSources)
            }
        }
    }
}

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.setTokenUsage(
    turnIndex: Int,
    platformIdx: Int,
    usage: TokenUsageRecord
) {
    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            val boundUsage = usage.withBinding(
                turnIndex = turnIndex,
                platformIndex = platformIdx,
                messageId = currentMessage.id
            )
            if (currentMessage.tokenUsage == boundUsage) {
                currentMessage
            } else {
                currentMessage.copy(tokenUsage = boundUsage)
            }
        }
    }
}

private class StreamingMessageBuffer {
    private val lock = Any()
    private val thoughts = StringBuilder()
    private val content = StringBuilder()
    private var publishedThoughtLength = 0
    private var publishedContentLength = 0

    fun appendThought(chunk: String): AppendResult = synchronized(lock) {
        when {
            chunk.isEmpty() -> AppendResult.EMPTY
            thoughts.isEmpty() -> {
                thoughts.append(chunk)
                AppendResult.FIRST
            }

            else -> {
                thoughts.append(chunk)
                AppendResult.PENDING
            }
        }
    }

    fun appendContent(chunk: String): AppendResult = synchronized(lock) {
        when {
            chunk.isEmpty() -> AppendResult.EMPTY
            content.isEmpty() -> {
                content.append(chunk)
                AppendResult.FIRST
            }

            else -> {
                content.append(chunk)
                AppendResult.PENDING
            }
        }
    }

    fun publishPending(
        messageFlow: MutableStateFlow<ChatViewModel.GroupedMessages>,
        turnIndex: Int,
        platformIdx: Int
    ) = synchronized(lock) {
        publishBufferedText(messageFlow, turnIndex, platformIdx)
    }

    fun flush(
        messageFlow: MutableStateFlow<ChatViewModel.GroupedMessages>,
        turnIndex: Int,
        platformIdx: Int
    ) = synchronized(lock) {
        publishBufferedText(messageFlow, turnIndex, platformIdx)
    }

    private fun publishBufferedText(
        messageFlow: MutableStateFlow<ChatViewModel.GroupedMessages>,
        turnIndex: Int,
        platformIdx: Int
    ) {
        if (!hasPendingChanges()) return
        messageFlow.setBufferedText(
            turnIndex = turnIndex,
            platformIdx = platformIdx,
            content = content.toString(),
            thoughts = thoughts.toString()
        )
        publishedContentLength = content.length
        publishedThoughtLength = thoughts.length
    }

    private fun hasPendingChanges(): Boolean = content.length != publishedContentLength || thoughts.length != publishedThoughtLength
}

private enum class AppendResult {
    EMPTY,
    FIRST,
    PENDING
}

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.setBufferedText(
    turnIndex: Int,
    platformIdx: Int,
    content: String,
    thoughts: String
) {
    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            if (currentMessage.content == content && currentMessage.thoughts == thoughts) {
                currentMessage
            } else {
                currentMessage.copy(
                    content = content,
                    thoughts = thoughts
                )
            }
        }
    }
}

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.setErrorMessage(
    turnIndex: Int,
    platformIdx: Int,
    error: String,
    currentTimeProvider: () -> Long,
    revisionToAppend: AssistantRevision?
) {
    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            currentMessage.copy(
                content = buildAssistantErrorContent(currentMessage.content, error),
                createdAt = currentTimeProvider(),
                revisions = revisionToAppend
                    ?.let { listOf(it) + currentMessage.revisions }
                    ?: currentMessage.revisions
            )
        }
    }
}

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.setTimestamp(
    turnIndex: Int,
    platformIdx: Int,
    currentTimeProvider: () -> Long,
    revisionToAppend: AssistantRevision?
) {
    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            currentMessage.copy(
                createdAt = currentTimeProvider(),
                revisions = revisionToAppend
                    ?.let { listOf(it) + currentMessage.revisions }
                    ?: currentMessage.revisions
            ).resetActiveRevision()
        }
    }
}
