package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState

private const val GROQ_THINK_OPEN = "<think>"
private const val GROQ_THINK_CLOSE = "</think>"

internal class GroqReasoningParser {
    private val pendingContent = StringBuilder()
    private val pendingThought = StringBuilder()
    private var insideThinkBlock = false
    private var trimLeadingWhitespaceOnNextContent = false

    fun append(
        contentChunk: String? = null,
        reasoningChunk: String? = null
    ): List<ApiState> {
        val emitted = mutableListOf<ApiState>()

        reasoningChunk
            ?.takeIf { it.isNotBlank() }
            ?.let { emitted += ApiState.Thinking(it) }

        if (contentChunk.isNullOrEmpty()) {
            return emitted
        }

        pendingContent.append(contentChunk)
        emitted += drain(finalFlush = false)
        return emitted
    }

    fun flush(): List<ApiState> {
        val emitted = drain(finalFlush = true).toMutableList()
        if (insideThinkBlock) {
            emitted += emitVisibleText(
                buildString {
                    append(GROQ_THINK_OPEN)
                    append(pendingThought)
                    append(pendingContent)
                }
            )
            pendingThought.clear()
            pendingContent.clear()
            insideThinkBlock = false
        } else if (pendingContent.isNotEmpty()) {
            emitted += emitVisibleText(pendingContent.toString())
            pendingContent.clear()
        }
        return emitted
    }

    private fun drain(finalFlush: Boolean): List<ApiState> {
        val emitted = mutableListOf<ApiState>()

        // Intentional state-machine loop: continue after consuming a full tag,
        // break only when we need more data or the final flush has no more tags.
        while (pendingContent.isNotEmpty()) {
            if (insideThinkBlock) {
                val closingIndex = pendingContent.indexOf(GROQ_THINK_CLOSE)
                if (closingIndex >= 0) {
                    pendingThought.append(pendingContent.substring(0, closingIndex))
                    pendingContent.delete(0, closingIndex + GROQ_THINK_CLOSE.length)

                    val thought = pendingThought.toString().trim()
                    if (thought.isNotEmpty()) {
                        emitted += ApiState.Thinking(thought)
                    }

                    pendingThought.clear()
                    insideThinkBlock = false
                    trimLeadingWhitespaceOnNextContent = true
                    continue
                }

                if (finalFlush) {
                    break
                }

                val safeLength = pendingContent.length - partialSuffixLength(
                    pendingContent,
                    GROQ_THINK_CLOSE
                )
                if (safeLength <= 0) break

                pendingThought.append(pendingContent.substring(0, safeLength))
                pendingContent.delete(0, safeLength)
                break
            }

            val openingIndex = pendingContent.indexOf(GROQ_THINK_OPEN)
            if (openingIndex >= 0) {
                emitted += emitVisibleText(pendingContent.substring(0, openingIndex))
                pendingContent.delete(0, openingIndex + GROQ_THINK_OPEN.length)
                insideThinkBlock = true
                continue
            }

            if (finalFlush) {
                break
            }

            val safeLength = pendingContent.length - partialSuffixLength(
                pendingContent,
                GROQ_THINK_OPEN
            )
            if (safeLength <= 0) break

            emitted += emitVisibleText(pendingContent.substring(0, safeLength))
            pendingContent.delete(0, safeLength)
            break
        }

        return emitted
    }

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

        if (visibleText.isEmpty()) {
            return emptyList()
        }

        return listOf(ApiState.Success(visibleText))
    }

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
}
