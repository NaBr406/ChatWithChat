package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState

private data class ReasoningTag(
    val opening: String,
    val closing: String
)

private val REASONING_TAGS = listOf(
    ReasoningTag(opening = "<think>", closing = "</think>"),
    ReasoningTag(opening = "<thinking>", closing = "</thinking>")
)

private enum class ReasoningSource {
    NONE,
    STRUCTURED,
    TAGGED
}

internal class ReasoningStreamParser {
    private val pendingContent = StringBuilder()
    private var activeTag: ReasoningTag? = null
    private var reasoningSource = ReasoningSource.NONE
    private var tagParsingDisabled = false
    private var trimLeadingWhitespaceOnNextThought = false
    private var trimLeadingWhitespaceOnNextContent = false

    fun append(
        contentChunk: String? = null,
        reasoningChunk: String? = null
    ): List<ApiState> {
        val emitted = mutableListOf<ApiState>()

        when (reasoningSource) {
            ReasoningSource.NONE ->
                reasoningChunk
                    ?.takeIf { it.isNotBlank() }
                    ?.let { chunk ->
                        reasoningSource = ReasoningSource.STRUCTURED
                        emitted += ApiState.Thinking(chunk)
                    }

            ReasoningSource.STRUCTURED ->
                reasoningChunk
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emitted += ApiState.Thinking(it) }

            ReasoningSource.TAGGED -> Unit
        }

        if (contentChunk.isNullOrEmpty()) {
            return emitted
        }

        pendingContent.append(contentChunk)
        emitted += drain(finalFlush = false)
        return emitted
    }

    fun flush(): List<ApiState> {
        val emitted = drain(finalFlush = true).toMutableList()
        if (activeTag != null) {
            emitted += emitTaggedThought(pendingContent.toString())
            pendingContent.clear()
            activeTag = null
            trimLeadingWhitespaceOnNextThought = false
        } else if (pendingContent.isNotEmpty()) {
            emitted += emitVisibleText(pendingContent.toString())
            pendingContent.clear()
        }
        return emitted
    }

    private fun drain(finalFlush: Boolean): List<ApiState> {
        val emitted = mutableListOf<ApiState>()

        // Continue after consuming a full tag; stop only when a split tag needs more data.
        while (pendingContent.isNotEmpty()) {
            if (tagParsingDisabled) {
                emitted += emitVisibleText(pendingContent.toString())
                pendingContent.clear()
                break
            }

            val currentTag = activeTag
            if (currentTag != null) {
                val closingIndex = pendingContent.indexOf(currentTag.closing)
                if (closingIndex >= 0) {
                    emitted += emitTaggedThought(pendingContent.substring(0, closingIndex))
                    pendingContent.delete(0, closingIndex + currentTag.closing.length)

                    activeTag = null
                    trimLeadingWhitespaceOnNextThought = false
                    trimLeadingWhitespaceOnNextContent = true
                    continue
                }

                if (finalFlush) {
                    break
                }

                val safeLength = pendingContent.length - partialSuffixLength(
                    pendingContent,
                    listOf(currentTag.closing)
                )
                if (safeLength <= 0) break

                emitted += emitTaggedThought(pendingContent.substring(0, safeLength))
                pendingContent.delete(0, safeLength)
                break
            }

            val openingMatch = findOpeningTag(pendingContent)
            if (openingMatch != null) {
                val visiblePrefix = pendingContent.substring(0, openingMatch.index)
                if (visiblePrefix.isNotBlank()) {
                    tagParsingDisabled = true
                    continue
                }
                pendingContent.delete(0, openingMatch.index + openingMatch.tag.opening.length)
                activeTag = openingMatch.tag
                trimLeadingWhitespaceOnNextThought = true
                continue
            }

            if (finalFlush) {
                break
            }

            val safeLength = pendingContent.length - partialSuffixLength(
                pendingContent,
                REASONING_TAGS.map { it.opening }
            )
            if (safeLength <= 0) break

            emitted += emitVisibleText(pendingContent.substring(0, safeLength))
            pendingContent.delete(0, safeLength)
            break
        }

        return emitted
    }

    private fun emitTaggedThought(text: String): List<ApiState> {
        if (text.isEmpty() || reasoningSource == ReasoningSource.STRUCTURED) return emptyList()

        val thoughtText = if (trimLeadingWhitespaceOnNextThought) {
            val trimmed = text.trimStart()
            if (trimmed.isNotEmpty()) {
                trimLeadingWhitespaceOnNextThought = false
            }
            trimmed
        } else {
            text
        }
        if (thoughtText.isEmpty()) return emptyList()

        reasoningSource = ReasoningSource.TAGGED
        return listOf(ApiState.Thinking(thoughtText))
    }

    private fun findOpeningTag(buffer: StringBuilder): OpeningTagMatch? = REASONING_TAGS
        .mapNotNull { tag ->
            buffer.indexOf(tag.opening)
                .takeIf { it >= 0 }
                ?.let { index -> OpeningTagMatch(index = index, tag = tag) }
        }
        .minByOrNull { it.index }

    private fun emitVisibleText(text: String): List<ApiState> {
        if (text.isEmpty()) return emptyList()

        val visibleText = if (trimLeadingWhitespaceOnNextContent) {
            val trimmed = text.trimStart()
            if (trimmed.isNotEmpty()) {
                trimLeadingWhitespaceOnNextContent = false
            }
            trimmed
        } else {
            text
        }

        if (visibleText.isNotBlank()) {
            tagParsingDisabled = true
        }

        return visibleText
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(ApiState.Success(it)) }
            .orEmpty()
    }

    private fun partialSuffixLength(buffer: StringBuilder, tokens: List<String>): Int =
        tokens.maxOfOrNull { token -> partialSuffixLength(buffer, token) } ?: 0

    private fun partialSuffixLength(buffer: StringBuilder, token: String): Int {
        val maxLength = minOf(buffer.length, token.length - 1)
        for (length in maxLength downTo 1) {
            if (buffer.endsWith(token.take(length))) {
                return length
            }
        }
        return 0
    }

    private fun StringBuilder.endsWith(suffix: String): Boolean {
        if (suffix.length > length) return false
        for (index in suffix.indices) {
            if (this[length - suffix.length + index] != suffix[index]) {
                return false
            }
        }
        return true
    }

    private data class OpeningTagMatch(
        val index: Int,
        val tag: ReasoningTag
    )
}
