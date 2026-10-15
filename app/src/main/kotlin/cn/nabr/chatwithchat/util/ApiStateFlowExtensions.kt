package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.data.context.stripInternalStickerMarkers
import cn.nabr.chatwithchat.data.database.entity.AssistantRevision
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.MessageStickerRef
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
private const val PARAGRAPH_BREAK_NEWLINES = 2

suspend fun Flow<ApiState>.handleStates(
    messageFlow: MutableStateFlow<ChatViewModel.GroupedMessages>,
    turnIndex: Int,
    platformIdx: Int,
    onLoadingComplete: () -> Unit,
    currentTimeProvider: () -> Long = { System.currentTimeMillis() / 1000 },
    revisionToAppendOnSuccess: AssistantRevision? = null,
    onToolProgress: (ApiState) -> Unit = {},
    /**
     * Maps an internal error code to display text before it is written into the message.
     *
     * The repository emits codes like `tool_arguments_too_large` as data — it has no business resolving
     * string resources — and this is the last point before the code is baked into content the user reads.
     * Defaults to a pass-through so callers that do not care (and tests) see the code unchanged.
     */
    resolveErrorMessage: (String) -> String = { message -> message },
    /**
     * Whether a cancellation came from the user pressing stop, rather than the screen going away.
     *
     * Only consulted when the stream did not finish, and it changes what the partial answer becomes. A
     * stop is a *completion*: the text already on screen is kept, pending stickers are attached and a
     * timestamp is written, exactly as if the model had finished there. Without that distinction a
     * stopped retry would be pure data loss — `revisionToAppendOnSuccess` carries the answer being
     * replaced, and it is only appended on the success branch, so the previous answer would vanish
     * along with the partial new one.
     *
     * Defaults to false, which reproduces the old behaviour: a turn that dies with its scope leaves the
     * message untimestamped and unsaved.
     */
    wasStoppedByUser: () -> Boolean = { false }
) = coroutineScope {
    val buffer = StreamingMessageBuffer()
    val pendingStickers = linkedMapOf<String, MessageStickerRef>()
    val publishSignals = Channel<Unit>(capacity = Channel.CONFLATED)
    val publisher = launch {
        for (signal in publishSignals) {
            delay(STREAM_PUBLISH_INTERVAL_MILLIS)
            buffer.publishPending(messageFlow, turnIndex, platformIdx)
        }
    }
    var isCompletedSuccessfully = false
    var terminalError: String? = null
    var streamCompletedNormally = false

    try {
        collect { chunk ->
            when (chunk) {
                is ApiState.StreamCheckpoint -> buffer.checkpoint(chunk.requestId)
                is ApiState.StreamRollback -> {
                    buffer.rollback(chunk.requestId)
                    buffer.publishPending(messageFlow, turnIndex, platformIdx)
                }
                is ApiState.StreamCheckpointReleased -> buffer.releaseCheckpoint(chunk.requestId)
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

                is ApiState.StickerAdded -> {
                    // Tool results can arrive while the model is still reasoning. Keep the
                    // presentation artifact local until the final answer completes.
                    pendingStickers.putIfAbsent(chunk.sticker.instanceId, chunk.sticker)
                }

                is ApiState.UsageUpdated -> {
                    messageFlow.setTokenUsage(turnIndex, platformIdx, chunk.usage)
                }

                ApiState.Done -> {
                    isCompletedSuccessfully = true
                }

                is ApiState.Error -> {
                    terminalError = resolveErrorMessage(chunk.message)
                }

                is ApiState.ToolStarted,
                is ApiState.ToolFinished,
                is ApiState.ToolFailed -> {
                    // 工具调用夹在两轮模型请求之间，是唯一可靠的轮次边界信号：
                    // 下一轮再吐正文/推理时要另起一段。
                    buffer.onRoundBoundary()
                    onToolProgress(chunk)
                }

                else -> {}
            }
        }
        streamCompletedNormally = true
    } finally {
        publishSignals.close()
        publisher.cancel()
        buffer.flush(messageFlow, turnIndex, platformIdx)
        val error = terminalError
        val stoppedByUser = !streamCompletedNormally && error == null && wasStoppedByUser()
        when {
            error != null -> messageFlow.setErrorMessage(
                turnIndex = turnIndex,
                platformIdx = platformIdx,
                error = error,
                currentTimeProvider = currentTimeProvider,
                revisionToAppend = revisionToAppendOnSuccess
            )

            (streamCompletedNormally && isCompletedSuccessfully) || stoppedByUser -> {
                pendingStickers.values.forEach { sticker ->
                    messageFlow.addStickerReference(turnIndex, platformIdx, sticker)
                }
                messageFlow.setTimestamp(
                    turnIndex = turnIndex,
                    platformIdx = platformIdx,
                    currentTimeProvider = currentTimeProvider,
                    revisionToAppend = revisionToAppendOnSuccess
                )
            }
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
    private var contentNeedsParagraphBreak = false
    private var thoughtsNeedParagraphBreak = false
    private val checkpoints = mutableMapOf<String, StreamBufferCheckpoint>()

    fun checkpoint(requestId: String) = synchronized(lock) {
        checkpoints[requestId] = StreamBufferCheckpoint(
            content.length,
            thoughts.length,
            contentNeedsParagraphBreak,
            thoughtsNeedParagraphBreak
        )
    }

    fun rollback(requestId: String) = synchronized(lock) {
        val checkpoint = checkpoints[requestId] ?: return@synchronized
        content.setLength(checkpoint.contentLength)
        thoughts.setLength(checkpoint.thoughtLength)
        contentNeedsParagraphBreak = checkpoint.contentNeedsParagraphBreak
        thoughtsNeedParagraphBreak = checkpoint.thoughtsNeedParagraphBreak
        publishedContentLength = -1
        publishedThoughtLength = -1
    }

    fun releaseCheckpoint(requestId: String) = synchronized(lock) {
        checkpoints.remove(requestId)
        Unit
    }

    /**
     * 一轮工具调用结束，下一轮的叙述和推理都属于新的段落。裸拼接会让两轮挤成一句
     * （"……试试。大哥，我直接拉原文试了下"），markdown 只看到一个超长段落，排版全丢。
     * 这里只是记账：真正补空行要等下一轮第一个 chunk 到了再补，否则一轮什么都不输出就会多出空行。
     */
    fun onRoundBoundary() = synchronized(lock) {
        contentNeedsParagraphBreak = contentNeedsParagraphBreak || content.isNotEmpty()
        thoughtsNeedParagraphBreak = thoughtsNeedParagraphBreak || thoughts.isNotEmpty()
    }

    fun appendThought(chunk: String): AppendResult = synchronized(lock) {
        if (chunk.isEmpty()) return@synchronized AppendResult.EMPTY

        val isFirstChunk = thoughts.isEmpty()
        if (thoughtsNeedParagraphBreak) {
            thoughts.append(paragraphBreak(thoughts, chunk))
            thoughtsNeedParagraphBreak = false
        }
        thoughts.append(chunk)
        if (isFirstChunk) AppendResult.FIRST else AppendResult.PENDING
    }

    fun appendContent(chunk: String): AppendResult = synchronized(lock) {
        if (chunk.isEmpty()) return@synchronized AppendResult.EMPTY

        val isFirstChunk = content.isEmpty()
        if (contentNeedsParagraphBreak) {
            content.append(paragraphBreak(content, chunk))
            contentNeedsParagraphBreak = false
        }
        content.append(chunk)
        if (isFirstChunk) AppendResult.FIRST else AppendResult.PENDING
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

private data class StreamBufferCheckpoint(
    val contentLength: Int,
    val thoughtLength: Int,
    val contentNeedsParagraphBreak: Boolean,
    val thoughtsNeedParagraphBreak: Boolean
)

/** 补到恰好隔一个空行。模型自己已经带的换行照算，不重复堆。 */
private fun paragraphBreak(buffered: StringBuilder, chunk: String): String {
    val existingNewlines = buffered.trailingNewlineCount() + chunk.leadingNewlineCount()
    return "\n".repeat((PARAGRAPH_BREAK_NEWLINES - existingNewlines).coerceAtLeast(0))
}

private fun StringBuilder.trailingNewlineCount(): Int {
    var count = 0
    while (count < length && this[length - 1 - count] == '\n') {
        count++
    }
    return count
}

private fun String.leadingNewlineCount(): Int = takeWhile { character -> character == '\n' }.length

private fun MutableStateFlow<ChatViewModel.GroupedMessages>.addStickerReference(
    turnIndex: Int,
    platformIdx: Int,
    sticker: MessageStickerRef
) {
    update { groupedMessages ->
        updateAssistantSlot(
            groupedMessages = groupedMessages,
            turnIndex = turnIndex,
            platformIndex = platformIdx
        ) { currentMessage ->
            if (currentMessage.stickerRefs.any { ref -> ref.instanceId == sticker.instanceId }) {
                currentMessage
            } else {
                currentMessage.copy(stickerRefs = currentMessage.stickerRefs + sticker)
            }
        }
    }
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
            val visibleContent = content.stripInternalStickerMarkers()
            val visibleThoughts = thoughts.stripInternalStickerMarkers()
            if (currentMessage.content == visibleContent && currentMessage.thoughts == visibleThoughts) {
                currentMessage
            } else {
                currentMessage.copy(
                    content = visibleContent,
                    thoughts = visibleThoughts
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
                content = buildAssistantErrorContent(currentMessage.content.stripInternalStickerMarkers(), error),
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
                content = currentMessage.content.stripInternalStickerMarkers(),
                createdAt = currentTimeProvider(),
                revisions = revisionToAppend
                    ?.let { listOf(it) + currentMessage.revisions }
                    ?: currentMessage.revisions
            ).resetActiveRevision()
        }
    }
}
